package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.PrAutoReviewConfig;
import com.codereview.agent.entity.PrAutoReviewDelivery;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.PrAutoReviewConfigMapper;
import com.codereview.agent.mapper.PrAutoReviewDeliveryMapper;
import com.codereview.agent.repository.PullRequestViews;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.codereview.common.BusinessException;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import com.codereview.git.GitUserRef;
import com.codereview.git.GiteaProperties;
import com.codereview.git.HostHttpException;
import com.codereview.git.IssueComment;
import com.codereview.git.IssueCommentPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * PR 自动审查的配置、Webhook 受理、任务创建和评论回写。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class PrAutoReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(PrAutoReviewService.class);
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie", "key", "apikey", "privatekey",
            "xhubsignature256", "credentials");

    public static final int LEASE_DURATION_SECONDS = 300;
    public static final int COMMENT_MAX_ATTEMPTS = 3;
    public static final int UNKNOWN_CHECK_DELAY_SECONDS = 60;
    public static final int MAX_TEXT_TARGETS = 500;
    public static final int MAX_CHANGED_FILES = 3000;
    private static final int SOURCE_MOVED_MAX_RETRIES = 3;
    private static final int[] COMMENT_BACKOFF_SECONDS = {30, 60, 120};

    private final AgentDatabase db;
    private final GitHostClientRegistry gitHostClients;
    private final ObjectMapper objectMapper;
    private final GiteaProperties giteaProperties;
    private final TaskService taskService;
    private final RepositoryReadService repositoryReadService;
    private final ExecutorService asyncExecutor;

    @Value("${git.webhook-secret:}")
    private String globalWebhookSecret;

    public PrAutoReviewService(AgentDatabase db, GitHostClientRegistry gitHostClients, ObjectMapper objectMapper,
                               GiteaProperties giteaProperties, TaskService taskService,
                               RepositoryReadService repositoryReadService) {
        this.db = db;
        this.gitHostClients = gitHostClients;
        this.objectMapper = objectMapper;
        this.giteaProperties = giteaProperties;
        this.taskService = taskService;
        this.repositoryReadService = repositoryReadService;
        this.asyncExecutor = Executors.newFixedThreadPool(4, new NamedThreadFactory("pr-auto-review-"));
    }

    @PreDestroy
    public void shutdown() {
        asyncExecutor.shutdown();
    }

    /** 读取项目自动审查配置；未配置时返回默认关闭的视图。 */
    public PrAutoReviewDtos.ConfigView getConfig(String projectId) {
        AgentProject project = requireProject(projectId);
        PrAutoReviewConfig config = db.mapper(PrAutoReviewConfigMapper.class)
                .selectOne(new LambdaQueryWrapper<PrAutoReviewConfig>()
                        .eq(PrAutoReviewConfig::getProjectId, project.getId()));

        if (config == null) {
            return new PrAutoReviewDtos.ConfigView(
                    null,
                    project.getId(),
                    false,
                    project.getCurrentBranch() != null ? project.getCurrentBranch() : "main",
                    null,
                    null,
                    null,
                    null,
                    false,
                    List.of(),
                    null,
                    null,
                    null,
                    defaultRepoFullName(project),
                    null,
                    null
            );
        }
        return toConfigView(config, project);
    }

    /** 更新或保存项目自动审查配置。 */
    public PrAutoReviewDtos.ConfigView updateConfig(String projectId, PrAutoReviewDtos.ConfigUpdate req) {
        AgentProject project = requireProject(projectId);

        String promptId = blankToNull(req.promptId());
        String promptVersionId = blankToNull(req.promptVersionId());
        if ((promptId == null) != (promptVersionId == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "选择通用规则时必须同时指定规则 ID 和具体版本");
        }

        if (req.modelConfigId() != null && !req.modelConfigId().isBlank()) {
            AgentModelConfig model = db.mapper(AgentModelConfigMapper.class).selectById(req.modelConfigId().trim());
            if (model == null || (model.getIsDeleted() != null && model.getIsDeleted() == 1)) {
                throw new ApiException(ErrorCode.NOT_FOUND, "指定的模型配置不存在: " + req.modelConfigId());
            }
        }

        PrAutoReviewConfig config = db.mapper(PrAutoReviewConfigMapper.class)
                .selectOne(new LambdaQueryWrapper<PrAutoReviewConfig>()
                        .eq(PrAutoReviewConfig::getProjectId, project.getId()));

        LocalDateTime now = AgentTime.now();
        boolean isNew = (config == null);
        if (isNew) {
            config = new PrAutoReviewConfig();
            config.setId(AgentIds.next());
            config.setProjectId(project.getId());
            config.setCreatedAt(now);
        }

        if (req.enabled() != null) {
            config.setEnabled(req.enabled() ? 1 : 0);
        } else if (isNew) {
            config.setEnabled(0);
        }

        if (req.targetBranch() != null && !req.targetBranch().isBlank()) {
            config.setTargetBranch(req.targetBranch().trim());
        } else if (isNew) {
            config.setTargetBranch(project.getCurrentBranch() != null ? project.getCurrentBranch() : "main");
        }

        config.setModelConfigId(blankToNull(req.modelConfigId()));
        config.setPromptId(promptId);
        config.setPromptVersionId(promptVersionId);
        config.setReviewBaseUrl(blankToNull(req.reviewBaseUrl()));

        if (req.webhookSecret() != null && !req.webhookSecret().isBlank()) {
            config.setWebhookSecret(req.webhookSecret().trim());
        }

        if (req.reviewers() != null) {
            try {
                config.setReviewers(objectMapper.writeValueAsString(req.reviewers()));
            } catch (Exception e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "序列化审核者列表失败: " + e.getMessage());
            }
        } else if (isNew) {
            config.setReviewers("[]");
        }

        if (req.botActorId() != null) {
            config.setBotActorId(req.botActorId());
        }
        if (req.botLogin() != null) {
            config.setBotLogin(blankToNull(req.botLogin()));
        }
        if (req.repoId() != null) {
            config.setRepoId(req.repoId());
        }
        if (req.repoFullName() != null) {
            config.setRepoFullName(blankToNull(req.repoFullName()));
        }

        config.setUpdatedAt(now);

        if (isNew) {
            db.mapper(PrAutoReviewConfigMapper.class).insert(config);
        } else {
            db.mapper(PrAutoReviewConfigMapper.class).updateById(config);
        }

        return toConfigView(config, project);
    }

    /** 审核者账号查询：调用对应宿主按用户名查询用户基础身份。 */
    public GitUserRef lookupReviewer(String projectId, String username) {
        AgentProject project = requireProject(projectId);
        if (username == null || username.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "用户名不能为空");
        }
        GitRepoRef ref;
        try {
            ref = GitRepoRef.parse(project.getRepoUrl());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "仓库地址无法解析: " + project.getRepoUrl());
        }

        GitHostClient client = gitHostClients.forRepo(ref);
        try {
            return client.findUserByUsername(project.getCredential(), project.getCredentialType(), ref, username.trim());
        } catch (HostHttpException e) {
            if (e.statusCode() == 404) {
                throw new ApiException(ErrorCode.NOT_FOUND, "未找到此账号，或当前凭据无法查看");
            }
            throw new ApiException(ErrorCode.REPO_UNAVAILABLE, "查询宿主用户失败: " + e.getMessage());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.REPO_UNAVAILABLE, "查询宿主用户失败: " + e.getMessage());
        }
    }

    /** 按服务端仓库绑定选择验签密钥，验证原始载荷后原子持久化投递。 */
    public PrAutoReviewDtos.WebhookReceiveResult receiveWebhook(String hostType,
                                                               String requestedInstance,
                                                               String deliveryId,
                                                               String eventName,
                                                               String eventType,
                                                               String signatureHeader,
                                                               byte[] rawPayload) {
        String normalizedHost = hostType == null ? "" : hostType.strip().toLowerCase(Locale.ROOT);
        if (!"github".equals(normalizedHost) && !"gitea".equals(normalizedHost)) {
            return recordRejected("HOST_UNSUPPORTED", "不支持该 Webhook 宿主");
        }
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 64) {
            return recordRejected("DELIVERY_ID_INVALID", "Webhook 投递 ID 缺失或超长");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return recordRejected("SIGNATURE_INVALID", "Webhook 验签失败");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            return recordRejected("PAYLOAD_INVALID", "请求体非合法 JSON 格式");
        }
        if (root == null || !root.isObject()) {
            return recordRejected("PAYLOAD_INVALID", "Webhook 请求体须为 JSON 对象");
        }

        Long repoId = positiveId(root.path("repository").path("id"));
        String repoFullName = root.path("repository").path("full_name").asText(null);

        List<BoundRepository> repositories = configuredRepositories(normalizedHost);
        String instance = resolveInstance(normalizedHost, requestedInstance, repositories);
        if (instance == null) {
            return recordRejected("HOST_INSTANCE_INVALID", "Webhook 入口须指定已绑定的宿主实例");
        }

        List<ProjectBinding> bindings = resolveProjects(repositories, instance, repoId, repoFullName);
        boolean signatureValid = bindings.isEmpty()
                ? verifySignature(normalizedHost, globalWebhookSecret, rawPayload, signatureHeader)
                : bindings.stream().anyMatch(binding -> verifySignature(normalizedHost,
                        effectiveSecret(binding.config()), rawPayload, signatureHeader));
        if (!signatureValid) {
            return recordRejected("SIGNATURE_INVALID", "Webhook 验签失败");
        }

        LocalDateTime now = AgentTime.now();
        PrAutoReviewDelivery delivery = new PrAutoReviewDelivery();
        delivery.setId(AgentIds.next());
        delivery.setHostType(normalizedHost);
        delivery.setHostInstanceKey(instance);
        delivery.setDeliveryId(deliveryId);
        delivery.setFirstReceivedAt(now);
        delivery.setLastReceivedAt(now);
        delivery.setReceiveCount(1);
        delivery.setEventName(eventName);
        delivery.setEventType(eventType);
        delivery.setEventAction(root.path("action").asText(null));
        delivery.setRepoId(repoId);
        delivery.setRepoFullName(repoFullName);

        JsonNode number = "issue_comment".equals(eventName) ? root.path("issue").path("number") : root.path("number");
        delivery.setPrNumber(positiveId(number));
        delivery.setActorUserId(positiveId(root.path("sender").path("id")));
        delivery.setActorLogin(root.path("sender").path("login").asText(null));
        delivery.setSourceCommentId(positiveId(root.path("comment").path("id")));

        delivery.setPayloadSnapshot(desensitizePayload(root));
        delivery.setRevision(0);
        delivery.setCommentState("NONE");
        delivery.setCommentAttempts(0);
        delivery.setUpdatedAt(now);

        evaluateDecision(delivery, bindings, root);

        PrAutoReviewDtos.WebhookReceiveResult result = db.inTransaction(() -> {
            PrAutoReviewDeliveryMapper mapper = db.mapper(PrAutoReviewDeliveryMapper.class);
            mapper.upsertReceived(delivery);
            PrAutoReviewDelivery stored = mapper.selectOne(new LambdaQueryWrapper<PrAutoReviewDelivery>()
                    .select(PrAutoReviewDelivery::getId, PrAutoReviewDelivery::getDecisionState,
                            PrAutoReviewDelivery::getDecisionCode, PrAutoReviewDelivery::getDecisionMessage,
                            PrAutoReviewDelivery::getReceiveCount)
                    .eq(PrAutoReviewDelivery::getHostType, normalizedHost)
                    .eq(PrAutoReviewDelivery::getHostInstanceKey, instance)
                    .eq(PrAutoReviewDelivery::getDeliveryId, deliveryId));
            return toReceiveResult(stored);
        });

        if ("RECEIVED".equals(result.decisionState())) {
            dispatchDeliveryAsync(result.deliveryId());
        }
        return result;
    }

    /** 查询某项目的投递处理记录列表。 */
    public PageResponse<PrAutoReviewDtos.DeliveryView> listDeliveries(String projectId,
                                                                      String eventName,
                                                                      String decisionState,
                                                                      String cursor,
                                                                      Integer limit,
                                                                      Integer pageNum,
                                                                      Integer pageSize) {
        return listDeliveries(projectId, eventName, decisionState, null, null, null, null,
                cursor, limit, pageNum, pageSize);
    }

    /** 查询某项目的投递处理记录列表，支持按事件原值、审查决定、原因码、回写状态、PR 编号及关键字筛选。 */
    public PageResponse<PrAutoReviewDtos.DeliveryView> listDeliveries(String projectId,
                                                                      String eventName,
                                                                      String decisionState,
                                                                      String decisionCode,
                                                                      String commentState,
                                                                      Long prNumber,
                                                                      String q,
                                                                      String cursor,
                                                                      Integer limit,
                                                                      Integer pageNum,
                                                                      Integer pageSize) {
        requireProject(projectId);
        LambdaQueryWrapper<PrAutoReviewDelivery> wrapper = new LambdaQueryWrapper<PrAutoReviewDelivery>()
                .eq(PrAutoReviewDelivery::getProjectId, projectId);

        if (eventName != null && !eventName.isBlank()) {
            String ev = eventName.trim();
            wrapper.and(w -> w.eq(PrAutoReviewDelivery::getEventType, ev)
                    .or(fallback -> fallback.isNull(PrAutoReviewDelivery::getEventType)
                            .eq(PrAutoReviewDelivery::getEventName, ev)));
        }
        if (decisionState != null && !decisionState.isBlank()) {
            wrapper.eq(PrAutoReviewDelivery::getDecisionState, decisionState.trim().toUpperCase(Locale.ROOT));
        }
        if (decisionCode != null && !decisionCode.isBlank()) {
            wrapper.eq(PrAutoReviewDelivery::getDecisionCode, decisionCode.trim().toUpperCase(Locale.ROOT));
        }
        if (commentState != null && !commentState.isBlank()) {
            wrapper.eq(PrAutoReviewDelivery::getCommentState, commentState.trim().toUpperCase(Locale.ROOT));
        }
        if (prNumber != null && prNumber > 0) {
            wrapper.eq(PrAutoReviewDelivery::getPrNumber, prNumber);
        }
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            String digits = keyword.startsWith("#") ? keyword.substring(1).trim() : keyword;
            Long parsedPr = parsePositiveLong(digits);
            wrapper.and(w -> {
                w.like(PrAutoReviewDelivery::getDeliveryId, keyword)
                        .or().like(PrAutoReviewDelivery::getReviewTaskId, keyword)
                        .or().like(PrAutoReviewDelivery::getDecisionCode, keyword)
                        .or().like(PrAutoReviewDelivery::getDecisionMessage, keyword)
                        .or().like(PrAutoReviewDelivery::getActorLogin, keyword)
                        .or().like(PrAutoReviewDelivery::getSourceHead, keyword);
                if (parsedPr != null) {
                    w.or().eq(PrAutoReviewDelivery::getPrNumber, parsedPr);
                }
            });
        }

        PrAutoReviewDeliveryMapper mapper = db.mapper(PrAutoReviewDeliveryMapper.class);
        PageResponse<PrAutoReviewDelivery> page = AgentPaging.query(
                mapper::selectWithOutcome,
                mapper::countWithOutcome,
                wrapper,
                cursor,
                limit,
                pageNum,
                pageSize,
                PrAutoReviewDelivery::getFirstReceivedAt,
                PrAutoReviewDelivery::getId
        );

        return page.withItems(page.items().stream().map(this::toDeliveryView).toList());
    }

    /** 查询单条投递处理记录详情。 */
    public PrAutoReviewDtos.DeliveryDetailView getDelivery(String projectId, String deliveryId) {
        requireProject(projectId);
        List<PrAutoReviewDelivery> deliveries = db.mapper(PrAutoReviewDeliveryMapper.class)
                .selectWithOutcome(new LambdaQueryWrapper<PrAutoReviewDelivery>()
                        .eq(PrAutoReviewDelivery::getProjectId, projectId)
                        .eq(PrAutoReviewDelivery::getId, deliveryId));

        if (deliveries.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "未找到指定的投递处理记录");
        }
        return toDeliveryDetailView(deliveries.get(0));
    }

    /** 查询某项目已收到投递的事件原值列表（优先 event_type，缺失时取 event_name）。 */
    public List<String> listDeliveryEvents(String projectId) {
        requireProject(projectId);
        List<String> events = db.mapper(PrAutoReviewDeliveryMapper.class).selectDistinctEvents(projectId);
        return events == null ? List.of() : events.stream().filter(Objects::nonNull).toList();
    }

    /** 业务幂等键计算（符合 PRA-01 契约规范）。 */
    public String calculateBusinessKey(PrAutoReviewDelivery delivery) {
        try {
            Object[] vector;
            if ("RECHECK".equalsIgnoreCase(delivery.getTriggerKind())) {
                vector = new Object[]{
                        delivery.getHostType(),
                        delivery.getHostInstanceKey(),
                        delivery.getRepoId(),
                        delivery.getPrNumber(),
                        "RECHECK",
                        delivery.getSourceCommentId()
                };
            } else {
                vector = new Object[]{
                        delivery.getHostType(),
                        delivery.getHostInstanceKey(),
                        delivery.getRepoId(),
                        delivery.getPrNumber(),
                        "FIRST"
                };
            }
            String json = objectMapper.writeValueAsString(vector);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return "pr-auto-review:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("计算业务幂等键失败: " + e.getMessage(), e);
        }
    }

    /** 审查详情链接格式化。 */
    public String formatReviewUrl(String baseUrl, String taskId) {
        return normalizeBaseUrl(baseUrl) + "/#/reviews/" + taskId + "?from=/reviews";
    }

    /** 回写评论正文格式化。 */
    public String formatCommentBody(String sourceHead, String reviewUrl) {
        String shortSha = sourceHead != null && sourceHead.length() >= 7 ? sourceHead.substring(0, 7) : (sourceHead != null ? sourceHead : "");
        return String.format("[查看提交 %s 的审查进度与结果](%s)", shortSha, reviewUrl);
    }

    /** 领取并处理一条投递。任务恢复先找回原任务，再考虑重新创建。 */
    public void processDelivery(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return;
        }
        PrAutoReviewDelivery delivery = db.mapper(PrAutoReviewDeliveryMapper.class).selectById(deliveryId);
        if (delivery == null) {
            return;
        }
        if ("CREATED".equals(delivery.getDecisionState())) {
            if ("PENDING".equals(delivery.getCommentState())) {
                processComment(delivery);
            } else if ("UNKNOWN".equals(delivery.getCommentState())) {
                reconcileComment(delivery);
            }
            return;
        }
        LocalDateTime now = AgentTime.now();
        if (!"RECEIVED".equals(delivery.getDecisionState()) || !isDue(delivery, now)
                || !claimDelivery(delivery, now)) {
            return;
        }

        String projectId = delivery.getProjectId();
        if (projectId == null) {
            skipDelivery(delivery, "PROJECT_NOT_BOUND", "未匹配到已绑定的系统项目");
            return;
        }
        AgentReviewTask original = findOriginalTask(delivery);
        PrAutoReviewConfig cfg = findConfig(projectId);
        if (original != null) {
            completeExistingTask(delivery, cfg, original);
            return;
        }

        AgentProject project = db.mapper(AgentProjectMapper.class).selectById(projectId);
        if (project == null || Integer.valueOf(1).equals(project.getIsDeleted())) {
            skipDelivery(delivery, "PROJECT_NOT_BOUND", "未匹配到已绑定的系统项目");
            return;
        }
        if (cfg == null || !Integer.valueOf(1).equals(cfg.getEnabled())) {
            skipDelivery(delivery, "AUTO_REVIEW_DISABLED", "项目未启用 PR 自动审查");
            return;
        }
        if ("RECHECK".equals(delivery.getTriggerKind())) {
            if (isBotComment(cfg, delivery)) {
                skipDelivery(delivery, "BOT_COMMENT", "机器人自身发言，跳过回写触发");
                return;
            }
            if (!isReviewerAllowed(cfg, delivery.getActorUserId())) {
                skipDelivery(delivery, "REVIEWER_NOT_ALLOWED", "触发人 ID 不在审核者名单中");
                return;
            }
        }

        String expectedBranch = cfg.getTargetBranch() != null ? cfg.getTargetBranch() : "main";
        TaskDtos.Create request;
        if (delivery.getTaskRequestSnapshot() != null) {
            if (delivery.getTargetBranch() != null) {
                expectedBranch = delivery.getTargetBranch();
            }
            request = readFrozenTaskRequest(delivery);
        } else {
            String modelId = cfg.getModelConfigId();
            AgentModelConfig model = modelId == null || modelId.isBlank() ? null
                    : db.mapper(AgentModelConfigMapper.class).selectById(modelId);
            if (model == null || Integer.valueOf(1).equals(model.getIsDeleted())) {
                failDelivery(delivery, "CONFIG_INVALID", "自动审查配置缺少有效的模型配置");
                return;
            }
            request = freezeTaskRequest(delivery, cfg, expectedBranch, null);
        }
        if (request != null) {
            createTask(delivery, cfg, expectedBranch, request);
        }
    }

    private boolean claimDelivery(PrAutoReviewDelivery delivery, LocalDateTime now) {
        LocalDateTime leaseUntil = now.plusSeconds(LEASE_DURATION_SECONDS);
        var claim = atRevision(delivery)
                .eq(PrAutoReviewDelivery::getDecisionState, "RECEIVED")
                .and(w -> w.isNull(PrAutoReviewDelivery::getNextAttemptAt)
                        .or().le(PrAutoReviewDelivery::getNextAttemptAt, now))
                .set(PrAutoReviewDelivery::getDecisionState, "PROCESSING")
                .set(PrAutoReviewDelivery::getLeaseUntil, leaseUntil)
                .set(PrAutoReviewDelivery::getNextAttemptAt, leaseUntil);
        if (!advanceDelivery(delivery, claim)) {
            return false;
        }
        delivery.setDecisionState("PROCESSING");
        delivery.setLeaseUntil(leaseUntil);
        delivery.setNextAttemptAt(leaseUntil);
        return true;
    }

    private AgentReviewTask findOriginalTask(PrAutoReviewDelivery delivery) {
        if (delivery.getBusinessKey() == null) {
            return null;
        }
        return db.mapper(AgentReviewTaskMapper.class).selectOne(new LambdaQueryWrapper<AgentReviewTask>()
                .eq(AgentReviewTask::getProjectId, delivery.getProjectId())
                .eq(AgentReviewTask::getIdempotencyKey, delivery.getBusinessKey()));
    }

    private PrAutoReviewConfig findConfig(String projectId) {
        return db.mapper(PrAutoReviewConfigMapper.class).selectOne(new LambdaQueryWrapper<PrAutoReviewConfig>()
                .eq(PrAutoReviewConfig::getProjectId, projectId));
    }

    private TaskDtos.Create readFrozenTaskRequest(PrAutoReviewDelivery delivery) {
        try {
            TaskDtos.Create request = objectMapper.readValue(delivery.getTaskRequestSnapshot(), TaskDtos.Create.class);
            if (request != null && delivery.getBusinessKey() != null
                    && delivery.getProjectId().equals(request.projectId())
                    && request.source() != null && "PR".equals(request.source().kind())
                    && Objects.equals(delivery.getPrNumber(), request.source().pullNumber())
                    && request.targets() != null && !request.targets().isEmpty()
                    && request.targets().size() <= MAX_TEXT_TARGETS) {
                return request;
            }
        } catch (JsonProcessingException e) {
            LOG.warn("无法读取投递 {} 的任务请求快照", delivery.getId());
        }
        failDelivery(delivery, "VALIDATION_FAILED", "已保存的自动审查请求无效");
        return null;
    }

    private TaskDtos.Create freezeTaskRequest(PrAutoReviewDelivery delivery, PrAutoReviewConfig cfg,
                                              String expectedBranch, TaskDtos.Create previous) {
        PullRequestViews.Changes changes;
        try {
            var detail = repositoryReadService.pullRequest(delivery.getProjectId(), delivery.getPrNumber());
            var pull = detail.pullRequest();
            if (previous == null && pull != null) {
                if (pull.targetBranch() != null && !pull.targetBranch().isBlank()) {
                    delivery.setTargetBranch(pull.targetBranch());
                }
                if (pull.sourceHead() != null && !pull.sourceHead().isBlank()) {
                    delivery.setSourceHead(pull.sourceHead());
                }
                if (pull.targetHead() != null && !pull.targetHead().isBlank()) {
                    delivery.setTargetHead(pull.targetHead());
                }
            }
            if (PullRequestViews.CROSS_REPOSITORY.equals(detail.blocker())) {
                skipDelivery(delivery, PullRequestViews.CROSS_REPOSITORY, "不支持跨仓库 PR 审查");
                return null;
            }
            if (PullRequestViews.PR_NOT_OPEN.equals(detail.blocker()) || !openPull(pull)) {
                skipDelivery(delivery, PullRequestViews.PR_NOT_OPEN, "PR 已关闭或已合并");
                return null;
            }
            if (!expectedBranch.equals(pull.targetBranch())) {
                skipDelivery(delivery, TaskService.TARGET_BRANCH_MISMATCH,
                        "目标分支不匹配配置的审查分支 " + expectedBranch);
                return null;
            }
            changes = repositoryReadService.pullRequestChanges(delivery.getProjectId(), delivery.getPrNumber());
        } catch (ApiException e) {
            recordTaskFailure(delivery, e);
            return null;
        } catch (RuntimeException e) {
            failDelivery(delivery, "REPO_READ_FAILED", "读取 PR 或净变化失败");
            return null;
        }

        if (PullRequestViews.CROSS_REPOSITORY.equals(changes.blocker())) {
            skipDelivery(delivery, PullRequestViews.CROSS_REPOSITORY, "不支持跨仓库 PR 审查");
            return null;
        }
        if (PullRequestViews.PR_NOT_OPEN.equals(changes.blocker())) {
            skipDelivery(delivery, PullRequestViews.PR_NOT_OPEN, "PR 已关闭或已合并");
            return null;
        }
        if (PullRequestViews.TRUNCATED.equals(changes.blocker())
                || PullRequestViews.TRUNCATED.equals(changes.completeness())
                || changes.fileCount() > MAX_CHANGED_FILES) {
            failDelivery(delivery, "SNAPSHOT_INCOMPLETE", "PR 净变化清单超过 3000 个文件上限，无法完整审查");
            return null;
        }
        if (PullRequestViews.NO_MERGE_BASE.equals(changes.blocker()) || changes.mergeBase() == null) {
            failDelivery(delivery, PullRequestViews.NO_MERGE_BASE, "源提交与目标分支没有共同祖先，无法计算净变化");
            return null;
        }

        List<TaskDtos.Target> targets = changes.files().stream()
                .filter(PullRequestViews.FileChange::text)
                .map(file -> new TaskDtos.Target(file.path(), file.oldPath(), null))
                .toList();
        if (previous == null) {
            recordPrChanges(delivery, expectedBranch, changes, targets.size());
        }
        if (targets.isEmpty()) {
            skipDelivery(delivery, "NO_TEXT_CHANGES", "PR 没有可审查的文本净变更");
            return null;
        }
        if (targets.size() > MAX_TEXT_TARGETS) {
            failDelivery(delivery, "VALIDATION_FAILED", "文本目标超过 500 个上限");
            return null;
        }

        String businessKey = delivery.getBusinessKey();
        if (businessKey == null) {
            businessKey = calculateBusinessKey(delivery);
            PrAutoReviewDelivery duplicate = findDuplicateDelivery(delivery, businessKey);
            if (duplicate != null) {
                skipDuplicateTrigger(delivery, duplicate.getId());
                return null;
            }
        }

        String head = changes.sourceHead();
        String shortSha = head == null ? "" : head.substring(0, Math.min(7, head.length()));
        var source = new TaskDtos.Source("PR", null, null, null, null, delivery.getPrNumber(),
                head, changes.targetHead(), changes.mergeBase());
        var request = new TaskDtos.Create(delivery.getProjectId(), "SPECIALIST", "ISSUE", source, targets,
                previous == null ? cfg.getModelConfigId() : previous.modelConfigId(),
                previous == null ? cfg.getPromptId() : previous.promptId(),
                previous == null ? cfg.getPromptVersionId() : previous.promptVersionId(),
                false, "PR #" + delivery.getPrNumber() + " 问题审查 · " + shortSha);

        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            failDelivery(delivery, "TASK_CREATE_FAILED", "无法保存自动审查请求");
            return null;
        }
        if (previous != null) {
            recordPrChanges(delivery, expectedBranch, changes, targets.size());
        }
        LocalDateTime leaseUntil = AgentTime.now().plusSeconds(LEASE_DURATION_SECONDS);
        try {
            var update = withFrozenSource(atRevision(delivery), delivery)
                    .eq(PrAutoReviewDelivery::getDecisionState, "PROCESSING")
                    .set(PrAutoReviewDelivery::getBusinessKey, businessKey)
                    .set(PrAutoReviewDelivery::getTaskRequestSnapshot, snapshot)
                    .set(PrAutoReviewDelivery::getLeaseUntil, leaseUntil)
                    .set(PrAutoReviewDelivery::getNextAttemptAt, leaseUntil);
            if (!advanceDelivery(delivery, update)) {
                return null;
            }
        } catch (DuplicateKeyException e) {
            PrAutoReviewDelivery winner = findDuplicateDelivery(delivery, businessKey);
            if (winner == null) {
                throw e;
            }
            skipDuplicateTrigger(delivery, winner.getId());
            return null;
        }
        delivery.setBusinessKey(businessKey);
        delivery.setTaskRequestSnapshot(snapshot);
        delivery.setLeaseUntil(leaseUntil);
        delivery.setNextAttemptAt(leaseUntil);
        return request;
    }

    private static void recordPrChanges(PrAutoReviewDelivery delivery, String targetBranch,
                                        PullRequestViews.Changes changes, int textCount) {
        delivery.setTargetBranch(targetBranch);
        delivery.setSourceHead(changes.sourceHead());
        delivery.setTargetHead(changes.targetHead());
        delivery.setMergeBase(changes.mergeBase());
        delivery.setTextCount(textCount);
        delivery.setBinaryCount(changes.binaryFiles());
        delivery.setUnreadableCount(changes.unreadableFiles());
    }

    private PrAutoReviewDelivery findDuplicateDelivery(PrAutoReviewDelivery delivery, String businessKey) {
        return db.mapper(PrAutoReviewDeliveryMapper.class).selectOne(new LambdaQueryWrapper<PrAutoReviewDelivery>()
                .eq(PrAutoReviewDelivery::getProjectId, delivery.getProjectId())
                .eq(PrAutoReviewDelivery::getBusinessKey, businessKey)
                .ne(PrAutoReviewDelivery::getId, delivery.getId()));
    }

    private void createTask(PrAutoReviewDelivery delivery, PrAutoReviewConfig cfg, String expectedBranch,
                            TaskDtos.Create initialRequest) {
        TaskDtos.Create request = initialRequest;
        for (int retries = 0; ; retries++) {
            TaskDtos.View task;
            try {
                task = taskService.create(request, delivery.getBusinessKey(), expectedBranch);
            } catch (RuntimeException e) {
                // 创建接口可能在事务提交后、返回任务视图前抛错。
                AgentReviewTask original = findOriginalTask(delivery);
                if (original != null) {
                    completeExistingTask(delivery, cfg, original);
                    return;
                }
                if (e instanceof ApiException api) {
                    if (api.errorCode() == ErrorCode.SOURCE_MOVED && retries < SOURCE_MOVED_MAX_RETRIES) {
                        request = freezeTaskRequest(delivery, cfg, expectedBranch, request);
                        if (request != null) {
                            continue;
                        }
                        return;
                    }
                    recordTaskFailure(delivery, api);
                } else {
                    failDelivery(delivery, "TASK_CREATE_FAILED", "创建自动审查任务失败");
                }
                return;
            }
            completeTask(delivery, cfg, task.id());
            return;
        }
    }

    private void recordTaskFailure(PrAutoReviewDelivery delivery, ApiException error) {
        Object reason = error.details() instanceof Map<?, ?> details ? details.get("reason") : null;
        if (TaskService.TARGET_BRANCH_MISMATCH.equals(reason) || PullRequestViews.PR_NOT_OPEN.equals(reason)
                || PullRequestViews.CROSS_REPOSITORY.equals(reason)) {
            skipDelivery(delivery, reason.toString(), error.getMessage());
        } else {
            String code = PullRequestViews.NO_MERGE_BASE.equals(reason) ? PullRequestViews.NO_MERGE_BASE
                    : error.errorCode().name();
            failDelivery(delivery, code, error.getMessage());
        }
    }

    private void completeExistingTask(PrAutoReviewDelivery delivery, PrAutoReviewConfig cfg, AgentReviewTask task) {
        recoverFromTaskSnapshot(delivery, task);
        completeTask(delivery, cfg, task.getId());
    }

    private void completeTask(PrAutoReviewDelivery delivery, PrAutoReviewConfig cfg, String taskId) {
        String reviewUrl = formatReviewUrl(cfg != null ? cfg.getReviewBaseUrl() : null, taskId);
        String body = formatCommentBody(delivery.getSourceHead(), reviewUrl);
        Long botId = cfg != null ? cfg.getBotActorId() : null;
        LocalDateTime now = AgentTime.now();
        var update = withFrozenSource(atRevision(delivery), delivery)
                .eq(PrAutoReviewDelivery::getDecisionState, "PROCESSING")
                .set(PrAutoReviewDelivery::getDecisionState, "CREATED")
                .set(PrAutoReviewDelivery::getDecidedAt, now)
                .set(PrAutoReviewDelivery::getDecisionCode, null)
                .set(PrAutoReviewDelivery::getDecisionMessage, null)
                .set(PrAutoReviewDelivery::getReviewTaskId, taskId)
                .set(PrAutoReviewDelivery::getReviewUrl, reviewUrl)
                .set(PrAutoReviewDelivery::getCommentActorId, botId)
                .set(PrAutoReviewDelivery::getCommentBody, body)
                .set(PrAutoReviewDelivery::getCommentState, "PENDING")
                .set(PrAutoReviewDelivery::getCommentAttempts, 0)
                .set(PrAutoReviewDelivery::getLeaseUntil, null)
                .set(PrAutoReviewDelivery::getNextAttemptAt, null);
        if (!advanceDelivery(delivery, update)) {
            return;
        }
        delivery.setDecisionState("CREATED");
        delivery.setDecidedAt(now);
        delivery.setDecisionCode(null);
        delivery.setDecisionMessage(null);
        delivery.setReviewTaskId(taskId);
        delivery.setReviewUrl(reviewUrl);
        delivery.setCommentActorId(botId);
        delivery.setCommentBody(body);
        delivery.setCommentState("PENDING");
        delivery.setCommentAttempts(0);
        delivery.setLeaseUntil(null);
        delivery.setNextAttemptAt(null);
        processComment(delivery);
    }

    private void recoverFromTaskSnapshot(PrAutoReviewDelivery delivery, AgentReviewTask task) {
        if (task.getInputSnapshot() == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(task.getInputSnapshot());
            JsonNode source = root.path("source");
            if (source.hasNonNull("targetBranch")) {
                delivery.setTargetBranch(source.path("targetBranch").asText());
            }
            if (source.hasNonNull("sourceHead")) {
                delivery.setSourceHead(source.path("sourceHead").asText());
            }
            if (source.hasNonNull("targetHead")) {
                delivery.setTargetHead(source.path("targetHead").asText());
            }
            if (source.hasNonNull("mergeBase")) {
                delivery.setMergeBase(source.path("mergeBase").asText());
            }
            JsonNode coverage = root.path("coverage");
            if (coverage.hasNonNull("textFiles")) {
                delivery.setTextCount(coverage.path("textFiles").asInt());
            }
            if (coverage.hasNonNull("binaryFiles")) {
                delivery.setBinaryCount(coverage.path("binaryFiles").asInt());
            }
            if (coverage.hasNonNull("unreadableFiles")) {
                delivery.setUnreadableCount(coverage.path("unreadableFiles").asInt());
            }
        } catch (JsonProcessingException e) {
            LOG.warn("无法读取已创建任务 {} 的输入快照", task.getId());
        }
    }

    /** 只允许成功领取 PENDING 记录的处理者发送一次评论。 */
    void processComment(PrAutoReviewDelivery delivery) {
        LocalDateTime now = AgentTime.now();
        if (!"CREATED".equals(delivery.getDecisionState()) || !"PENDING".equals(delivery.getCommentState())
                || !isDue(delivery, now)) {
            return;
        }
        if (delivery.getReviewTaskId() == null || delivery.getCommentBody() == null) {
            finishComment(delivery, "FAILED", null, "缺少已保存的审查任务或评论正文");
            return;
        }
        int attempts = commentAttempts(delivery);
        if (attempts >= COMMENT_MAX_ATTEMPTS) {
            finishComment(delivery, "FAILED", null, "超过最大评论尝试次数 (3次)");
            return;
        }

        AgentProject project = db.mapper(AgentProjectMapper.class).selectById(delivery.getProjectId());
        if (project == null || Integer.valueOf(1).equals(project.getIsDeleted())) {
            finishComment(delivery, "FAILED", null, "关联项目不存在");
            return;
        }
        GitRepoRef ref;
        GitHostClient client;
        try {
            ref = GitRepoRef.parse(project.getRepoUrl());
            client = gitHostClients.forRepo(ref);
        } catch (RuntimeException e) {
            finishComment(delivery, "FAILED", null, "关联仓库地址或宿主无效");
            return;
        }

        Long actorId = delivery.getCommentActorId();
        if (actorId == null || actorId <= 0) {
            actorId = resolveCommentActor(project, client, ref);
        }
        LocalDateTime claimTime = AgentTime.now();
        LocalDateTime leaseUntil = claimTime.plusSeconds(LEASE_DURATION_SECONDS);
        var claim = atRevision(delivery)
                .eq(PrAutoReviewDelivery::getDecisionState, "CREATED")
                .eq(PrAutoReviewDelivery::getCommentState, "PENDING")
                .and(w -> w.isNull(PrAutoReviewDelivery::getNextAttemptAt)
                        .or().le(PrAutoReviewDelivery::getNextAttemptAt, claimTime))
                .set(PrAutoReviewDelivery::getCommentState, "SENDING")
                .set(PrAutoReviewDelivery::getCommentAttempts, attempts + 1)
                .set(PrAutoReviewDelivery::getCommentActorId, actorId)
                .set(PrAutoReviewDelivery::getCommentLastAttemptAt, claimTime)
                .set(PrAutoReviewDelivery::getLeaseUntil, leaseUntil)
                .set(PrAutoReviewDelivery::getNextAttemptAt, leaseUntil);
        if (!advanceDelivery(delivery, claim)) {
            return;
        }
        delivery.setCommentState("SENDING");
        delivery.setCommentAttempts(attempts + 1);
        delivery.setCommentActorId(actorId);
        delivery.setCommentLastAttemptAt(claimTime);
        delivery.setLeaseUntil(leaseUntil);
        delivery.setNextAttemptAt(leaseUntil);

        IssueComment comment;
        try {
            comment = client.createIssueComment(project.getCredential(), project.getCredentialType(), ref,
                    delivery.getPrNumber(), delivery.getCommentBody());
        } catch (RuntimeException e) {
            handleCommentException(delivery, e);
            return;
        }
        if (comment == null || comment.id() <= 0) {
            handleCommentException(delivery, new IllegalStateException("宿主返回无效评论回执"));
            return;
        }
        delivery.setCommentId(comment.id());
        if (comment.author() != null && comment.author().id() > 0) {
            delivery.setCommentActorId(comment.author().id());
        }
        delivery.setCommentHttpStatus(201);
        delivery.setCommentCheckedAt(AgentTime.now());
        finishComment(delivery, "SENT", null, null);
    }

    private Long resolveCommentActor(AgentProject project, GitHostClient client, GitRepoRef ref) {
        PrAutoReviewConfig cfg = findConfig(project.getId());
        if (cfg == null) {
            return null;
        }
        if (cfg.getBotActorId() != null && cfg.getBotActorId() > 0) {
            return cfg.getBotActorId();
        }
        String login = blankToNull(cfg.getBotLogin());
        if (login == null) {
            return null;
        }
        try {
            GitUserRef actor = client.findUserByUsername(project.getCredential(), project.getCredentialType(), ref, login);
            return actor != null && actor.id() > 0 ? actor.id() : null;
        } catch (RuntimeException e) {
            LOG.warn("无法解析项目 {} 的评论机器人 ID", project.getId());
            return null;
        }
    }

    private void handleCommentException(PrAutoReviewDelivery delivery, RuntimeException error) {
        LocalDateTime now = AgentTime.now();
        Integer status = error instanceof HostHttpException http && !http.timeout() ? http.statusCode() : null;
        delivery.setCommentHttpStatus(status);
        String message = status == null ? "宿主评论回执无法确认" : "宿主返回 HTTP " + status;
        if (status == null || status == 408 || status == 502 || status == 503 || status == 504) {
            finishComment(delivery, "UNKNOWN", now.plusSeconds(UNKNOWN_CHECK_DELAY_SECONDS), message);
        } else if ((status >= 400 && status < 500 && status != 429)
                || commentAttempts(delivery) >= COMMENT_MAX_ATTEMPTS) {
            finishComment(delivery, "FAILED", null, message);
        } else {
            finishComment(delivery, "PENDING", nextCommentAttempt(delivery, now), message);
        }
    }

    /** 静默期过后按已保存的机器人 ID 和链接核对评论。 */
    void reconcileComment(PrAutoReviewDelivery delivery) {
        LocalDateTime now = AgentTime.now();
        if (!"CREATED".equals(delivery.getDecisionState()) || !"UNKNOWN".equals(delivery.getCommentState())
                || !isDue(delivery, now)
                || (delivery.getCommentLastAttemptAt() != null
                && now.isBefore(delivery.getCommentLastAttemptAt().plusSeconds(UNKNOWN_CHECK_DELAY_SECONDS)))) {
            return;
        }
        if (delivery.getCommentActorId() == null || delivery.getCommentActorId() <= 0
                || delivery.getReviewUrl() == null) {
            finishComment(delivery, "UNKNOWN", now.plusSeconds(UNKNOWN_CHECK_DELAY_SECONDS), "缺少已保存的机器人 ID 或审查链接");
            return;
        }
        AgentProject project = db.mapper(AgentProjectMapper.class).selectById(delivery.getProjectId());
        if (project == null || Integer.valueOf(1).equals(project.getIsDeleted())) {
            finishComment(delivery, "UNKNOWN", now.plusSeconds(UNKNOWN_CHECK_DELAY_SECONDS), "关联项目不存在，无法核对评论");
            return;
        }
        IssueComment posted;
        try {
            posted = findPostedComment(project, delivery);
        } catch (RuntimeException e) {
            finishComment(delivery, "UNKNOWN", AgentTime.now().plusSeconds(UNKNOWN_CHECK_DELAY_SECONDS),
                    "宿主评论列表读取未完成，等待再次核对");
            return;
        }

        LocalDateTime checkedAt = AgentTime.now();
        delivery.setCommentCheckedAt(checkedAt);
        if (posted != null) {
            delivery.setCommentId(posted.id());
            finishComment(delivery, "SENT", null, null);
        } else if (commentAttempts(delivery) >= COMMENT_MAX_ATTEMPTS) {
            finishComment(delivery, "FAILED", null, "回执核对未找到评论，且已达最大尝试次数 (3次)");
        } else {
            finishComment(delivery, "PENDING", nextCommentAttempt(delivery, checkedAt), delivery.getCommentError());
        }
    }

    /** 只有完整读取到最后一页且未匹配时，才返回评论不存在。 */
    private IssueComment findPostedComment(AgentProject project, PrAutoReviewDelivery delivery) {
        GitRepoRef ref = GitRepoRef.parse(project.getRepoUrl());
        GitHostClient client = gitHostClients.forRepo(ref);
        String link = "(" + delivery.getReviewUrl() + ")";
        Set<Long> pageEnds = new HashSet<>();
        for (int page = 1; ; page++) {
            IssueCommentPage result = client.listIssueComments(project.getCredential(), project.getCredentialType(),
                    ref, delivery.getPrNumber(), page, 50);
            if (result == null || result.items() == null || (result.hasMore() && result.items().isEmpty())) {
                throw new IllegalStateException("宿主评论分页响应不完整");
            }
            List<IssueComment> items = result.items();
            for (IssueComment comment : items) {
                if (comment == null || comment.id() <= 0 || comment.body() == null) {
                    throw new IllegalStateException("宿主返回无效评论");
                }
                if (comment.body().contains(link)) {
                    if (comment.author() == null || comment.author().id() <= 0) {
                        throw new IllegalStateException("无法确认评论作者");
                    }
                    if (delivery.getCommentActorId() == comment.author().id()) {
                        return comment;
                    }
                }
            }
            if (!items.isEmpty() && !pageEnds.add(items.get(items.size() - 1).id())) {
                throw new IllegalStateException("宿主评论分页重复");
            }
            if (!result.hasMore()) {
                return null;
            }
        }
    }

    private void finishComment(PrAutoReviewDelivery delivery, String state, LocalDateTime nextAttempt, String error) {
        var update = atRevision(delivery)
                .eq(PrAutoReviewDelivery::getDecisionState, "CREATED")
                .eq(PrAutoReviewDelivery::getCommentState, delivery.getCommentState())
                .set(PrAutoReviewDelivery::getCommentState, state)
                .set(PrAutoReviewDelivery::getCommentId, delivery.getCommentId())
                .set(PrAutoReviewDelivery::getCommentActorId, delivery.getCommentActorId())
                .set(PrAutoReviewDelivery::getCommentHttpStatus, delivery.getCommentHttpStatus())
                .set(PrAutoReviewDelivery::getCommentError, error)
                .set(PrAutoReviewDelivery::getCommentCheckedAt, delivery.getCommentCheckedAt())
                .set(PrAutoReviewDelivery::getLeaseUntil, null)
                .set(PrAutoReviewDelivery::getNextAttemptAt, nextAttempt);
        if (advanceDelivery(delivery, update)) {
            delivery.setCommentState(state);
            delivery.setCommentError(error);
            delivery.setLeaseUntil(null);
            delivery.setNextAttemptAt(nextAttempt);
        }
    }

    private static int commentAttempts(PrAutoReviewDelivery delivery) {
        return delivery.getCommentAttempts() == null ? 0 : delivery.getCommentAttempts();
    }

    private static LocalDateTime nextCommentAttempt(PrAutoReviewDelivery delivery, LocalDateTime now) {
        int attempt = Math.max(1, commentAttempts(delivery));
        return now.plusSeconds(COMMENT_BACKOFF_SECONDS[attempt - 1]);
    }

    /** 回收已过期的任务创建或评论发送租约。 */
    void recoverExpiredLeases() {
        LocalDateTime now = AgentTime.now();
        List<PrAutoReviewDelivery> expired = db.mapper(PrAutoReviewDeliveryMapper.class).selectList(
                new LambdaQueryWrapper<PrAutoReviewDelivery>()
                        .le(PrAutoReviewDelivery::getLeaseUntil, now)
                        .and(w -> w.eq(PrAutoReviewDelivery::getDecisionState, "PROCESSING")
                                .or(s -> s.eq(PrAutoReviewDelivery::getDecisionState, "CREATED")
                                        .eq(PrAutoReviewDelivery::getCommentState, "SENDING")))
                        .orderByAsc(PrAutoReviewDelivery::getNextAttemptAt, PrAutoReviewDelivery::getId)
                        .last("limit 50"));
        for (PrAutoReviewDelivery delivery : expired) {
            boolean sending = "CREATED".equals(delivery.getDecisionState()) && "SENDING".equals(delivery.getCommentState());
            LocalDateTime nextAttempt = sending ? now.plusSeconds(UNKNOWN_CHECK_DELAY_SECONDS) : null;
            var update = atRevision(delivery)
                    .eq(PrAutoReviewDelivery::getDecisionState, delivery.getDecisionState())
                    .eq(PrAutoReviewDelivery::getCommentState, delivery.getCommentState())
                    .le(PrAutoReviewDelivery::getLeaseUntil, now)
                    .set(PrAutoReviewDelivery::getLeaseUntil, null)
                    .set(PrAutoReviewDelivery::getNextAttemptAt, nextAttempt);
            if (sending) {
                update.set(PrAutoReviewDelivery::getCommentState, "UNKNOWN");
            } else {
                update.set(PrAutoReviewDelivery::getDecisionState, "RECEIVED");
            }
            if (advanceDelivery(delivery, update)) {
                if (sending) {
                    delivery.setCommentState("UNKNOWN");
                } else {
                    delivery.setDecisionState("RECEIVED");
                }
                delivery.setLeaseUntil(null);
                delivery.setNextAttemptAt(nextAttempt);
            }
        }
    }

    private void processPendingDeliveries() {
        LocalDateTime now = AgentTime.now();
        List<PrAutoReviewDelivery> pending = db.mapper(PrAutoReviewDeliveryMapper.class).selectList(
                new LambdaQueryWrapper<PrAutoReviewDelivery>()
                        .eq(PrAutoReviewDelivery::getDecisionState, "RECEIVED")
                        .and(w -> w.isNull(PrAutoReviewDelivery::getNextAttemptAt)
                                .or().le(PrAutoReviewDelivery::getNextAttemptAt, now))
                        .orderByAsc(PrAutoReviewDelivery::getNextAttemptAt, PrAutoReviewDelivery::getId)
                        .last("limit 20"));
        for (PrAutoReviewDelivery delivery : pending) {
            processDelivery(delivery.getId());
        }
    }

    private List<PrAutoReviewDelivery> dueComments(String state) {
        LocalDateTime now = AgentTime.now();
        return db.mapper(PrAutoReviewDeliveryMapper.class).selectList(new LambdaQueryWrapper<PrAutoReviewDelivery>()
                .eq(PrAutoReviewDelivery::getDecisionState, "CREATED")
                .eq(PrAutoReviewDelivery::getCommentState, state)
                .and(w -> w.isNull(PrAutoReviewDelivery::getNextAttemptAt)
                        .or().le(PrAutoReviewDelivery::getNextAttemptAt, now))
                .orderByAsc(PrAutoReviewDelivery::getNextAttemptAt, PrAutoReviewDelivery::getId)
                .last("limit 20"));
    }

    @Scheduled(fixedDelayString = "${agent.pr-auto-review.sweep-millis:30000}")
    public void sweep() {
        try {
            recoverExpiredLeases();
            processPendingDeliveries();
            dueComments("PENDING").forEach(this::processComment);
            dueComments("UNKNOWN").forEach(this::reconcileComment);
        } catch (Exception e) {
            LOG.warn("PR 自动审查后台轮询异常", e);
        }
    }

    private static boolean isDue(PrAutoReviewDelivery delivery, LocalDateTime now) {
        return delivery.getNextAttemptAt() == null || !delivery.getNextAttemptAt().isAfter(now);
    }

    private static LambdaUpdateWrapper<PrAutoReviewDelivery> atRevision(PrAutoReviewDelivery delivery) {
        return new LambdaUpdateWrapper<PrAutoReviewDelivery>()
                .eq(PrAutoReviewDelivery::getId, delivery.getId())
                .eq(PrAutoReviewDelivery::getRevision, delivery.getRevision());
    }

    /** 推进状态时仅写本步骤拥有的字段，接收计数与时间由 Webhook 入库维护。 */
    private boolean advanceDelivery(PrAutoReviewDelivery delivery, LambdaUpdateWrapper<PrAutoReviewDelivery> update) {
        LocalDateTime now = AgentTime.now();
        int revision = delivery.getRevision() + 1;
        int affected = db.mapper(PrAutoReviewDeliveryMapper.class).update(null,
                update.set(PrAutoReviewDelivery::getRevision, revision).set(PrAutoReviewDelivery::getUpdatedAt, now));
        if (affected == 0) {
            return false;
        }
        delivery.setRevision(revision);
        delivery.setUpdatedAt(now);
        return true;
    }

    private static LambdaUpdateWrapper<PrAutoReviewDelivery> withFrozenSource(
            LambdaUpdateWrapper<PrAutoReviewDelivery> update, PrAutoReviewDelivery delivery) {
        return update.set(PrAutoReviewDelivery::getTargetBranch, delivery.getTargetBranch())
                .set(PrAutoReviewDelivery::getSourceHead, delivery.getSourceHead())
                .set(PrAutoReviewDelivery::getTargetHead, delivery.getTargetHead())
                .set(PrAutoReviewDelivery::getMergeBase, delivery.getMergeBase())
                .set(PrAutoReviewDelivery::getTextCount, delivery.getTextCount())
                .set(PrAutoReviewDelivery::getBinaryCount, delivery.getBinaryCount())
                .set(PrAutoReviewDelivery::getUnreadableCount, delivery.getUnreadableCount());
    }

    private void skipDuplicateTrigger(PrAutoReviewDelivery delivery, String canonicalRecordId) {
        finishDecision(delivery, "SKIPPED", "DUPLICATE_TRIGGER", "重复触发，关联至原处理记录", canonicalRecordId);
    }

    private void skipDelivery(PrAutoReviewDelivery delivery, String code, String message) {
        finishDecision(delivery, "SKIPPED", code, message, null);
    }

    private void failDelivery(PrAutoReviewDelivery delivery, String code, String message) {
        finishDecision(delivery, "FAILED", code, message, null);
    }

    private void finishDecision(PrAutoReviewDelivery delivery, String state, String code, String message,
                                String canonicalRecordId) {
        LocalDateTime now = AgentTime.now();
        String safeMessage = truncateMessage(message, 500);
        var update = withFrozenSource(atRevision(delivery), delivery)
                .eq(PrAutoReviewDelivery::getDecisionState, "PROCESSING")
                .set(PrAutoReviewDelivery::getDecisionState, state)
                .set(PrAutoReviewDelivery::getDecidedAt, now)
                .set(PrAutoReviewDelivery::getDecisionCode, code)
                .set(PrAutoReviewDelivery::getDecisionMessage, safeMessage)
                .set(PrAutoReviewDelivery::getCanonicalRecordId, canonicalRecordId)
                .set(canonicalRecordId != null, PrAutoReviewDelivery::getBusinessKey, null)
                .set(PrAutoReviewDelivery::getLeaseUntil, null)
                .set(PrAutoReviewDelivery::getNextAttemptAt, null);
        if (advanceDelivery(delivery, update)) {
            delivery.setDecisionState(state);
            delivery.setDecidedAt(now);
            delivery.setDecisionCode(code);
            delivery.setDecisionMessage(safeMessage);
            delivery.setCanonicalRecordId(canonicalRecordId);
            if (canonicalRecordId != null) {
                delivery.setBusinessKey(null);
            }
            delivery.setLeaseUntil(null);
            delivery.setNextAttemptAt(null);
        }
    }

    private static Long parsePositiveLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            long val = Long.parseLong(s);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:5173";
        }
        return url.strip().replaceAll("/+$", "");
    }

    private static String truncateMessage(String message, int maxLen) {
        if (message == null) {
            return null;
        }
        return message.length() <= maxLen ? message : message.substring(0, maxLen);
    }

    private volatile boolean enableAsyncDispatch = true;

    public void setEnableAsyncDispatch(boolean enableAsyncDispatch) {
        this.enableAsyncDispatch = enableAsyncDispatch;
    }

    private void dispatchDeliveryAsync(String deliveryId) {
        if (!enableAsyncDispatch) {
            return;
        }
        if (!asyncExecutor.isShutdown()) {
            try {
                asyncExecutor.execute(() -> {
                    try {
                        processDelivery(deliveryId);
                    } catch (Exception e) {
                        LOG.warn("异步处理 Webhook 投递异常: {}", e.getMessage(), e);
                    }
                });
            } catch (RejectedExecutionException e) {
                LOG.warn("异步线程池已满，投递 {} 等待定时任务处理", deliveryId);
            }
        }
    }

    private static boolean openPull(PullRequestViews.Item item) {
        return "open".equalsIgnoreCase(item.state()) && !item.merged();
    }

    // ---------------------------------------------------------------- 内部方法

    private void evaluateDecision(PrAutoReviewDelivery delivery, List<ProjectBinding> bindings, JsonNode root) {
        delivery.setDecidedAt(AgentTime.now());
        if (bindings.size() > 1) {
            skip(delivery, "CONFIG_CONFLICT", "存在多个匹配的系统项目");
            return;
        }
        if (bindings.isEmpty()) {
            skip(delivery, "PROJECT_NOT_BOUND", "未匹配到已绑定的系统项目");
            return;
        }

        ProjectBinding binding = bindings.get(0);
        delivery.setProjectId(binding.projectId());
        PrAutoReviewConfig cfg = binding.config();
        if (cfg == null || !Integer.valueOf(1).equals(cfg.getEnabled())) {
            skip(delivery, "AUTO_REVIEW_DISABLED", "项目未启用 PR 自动审查");
            return;
        }

        boolean gitea = "gitea".equals(delivery.getHostType());
        boolean pullRequest = "pull_request".equals(delivery.getEventName());
        String eventType = delivery.getEventType();
        String action = delivery.getEventAction();
        if (pullRequest && "opened".equals(action) && (!gitea || "pull_request".equals(eventType))) {
            if (delivery.getRepoId() == null || delivery.getPrNumber() == null) {
                skip(delivery, "PAYLOAD_INVALID", "新建 PR 事件缺少仓库 ID 或 PR 编号");
                return;
            }
            String targetBranch = root.path("pull_request").path("base").path("ref").asText("");
            delivery.setTargetBranch(targetBranch);
            delivery.setSourceHead(root.path("pull_request").path("head").path("sha").asText(null));

            String expectedBranch = cfg.getTargetBranch() != null ? cfg.getTargetBranch() : "main";
            if (!expectedBranch.equals(targetBranch)) {
                skip(delivery, "TARGET_BRANCH_MISMATCH", "目标分支不匹配配置的审查分支 " + expectedBranch);
                return;
            }
            delivery.setTriggerKind("FIRST");
            delivery.setDecisionState("RECEIVED");
            return;
        }

        if ("issue_comment".equals(delivery.getEventName())) {
            boolean prComment = gitea
                    ? "pull_request_comment".equals(eventType) && root.path("is_pull").booleanValue()
                    : root.path("issue").path("pull_request").isObject();
            if (!prComment) {
                skip(delivery, "ACTION_IGNORED", "普通 Issue 评论不触发自动审查");
                return;
            }
            if ("edited".equals(action)) {
                skip(delivery, "ACTION_IGNORED", "编辑旧评论不触发复审");
                return;
            }
            if ("deleted".equals(action)) {
                skip(delivery, "ACTION_IGNORED", "删除评论不触发复审");
                return;
            }
            if (!"created".equals(action)) {
                skip(delivery, "ACTION_IGNORED", "仅处理新建的 PR 普通评论");
                return;
            }
            if (isBotComment(cfg, delivery)) {
                skip(delivery, "BOT_COMMENT", "机器人自身发言，跳过回写触发");
                return;
            }

            if (!"/recheck".equals(root.path("comment").path("body").asText("").strip())) {
                skip(delivery, "COMMAND_MISMATCH", "评论正文未精确匹配 /recheck 命令");
                return;
            }
            Long actorId = delivery.getActorUserId();
            if (!isReviewerAllowed(cfg, actorId)) {
                skip(delivery, "REVIEWER_NOT_ALLOWED", "触发人 ID 不在审核者名单中");
                return;
            }
            if (delivery.getRepoId() == null || delivery.getPrNumber() == null || delivery.getSourceCommentId() == null) {
                skip(delivery, "PAYLOAD_INVALID", "复审事件缺少仓库 ID、PR 编号或评论 ID");
                return;
            }
            delivery.setTriggerKind("RECHECK");
            delivery.setDecisionState("RECEIVED");
            return;
        }

        if (pullRequest && (gitea
                ? ("pull_request_sync".equals(eventType) || "synchronized".equals(action))
                : "synchronize".equals(action))) {
            skip(delivery, "ACTION_IGNORED", "追加提交后由审核者发起 /recheck 复审");
            return;
        }
        skip(delivery, "EVENT_NOT_SUPPORTED", "该 Webhook 事件未配置审查触发规则");
    }

    private boolean isBotComment(PrAutoReviewConfig cfg, PrAutoReviewDelivery delivery) {
        if (cfg == null || delivery == null) {
            return false;
        }
        Long actorId = delivery.getActorUserId();
        if (actorId != null && actorId.equals(cfg.getBotActorId())) {
            return true;
        }
        if (cfg.getBotActorId() == null) {
            String botLogin = blankToNull(cfg.getBotLogin());
            String actorLogin = blankToNull(delivery.getActorLogin());
            return botLogin != null && actorLogin != null && botLogin.equalsIgnoreCase(actorLogin);
        }
        return false;
    }

    private static void skip(PrAutoReviewDelivery delivery, String code, String message) {
        delivery.setDecisionState("SKIPPED");
        delivery.setDecisionCode(code);
        delivery.setDecisionMessage(message);
    }

    private boolean isReviewerAllowed(PrAutoReviewConfig cfg, Long actorId) {
        if (actorId == null || cfg.getReviewers() == null || cfg.getReviewers().isBlank()) {
            return false;
        }
        try {
            List<PrAutoReviewDtos.ReviewerItem> list = objectMapper.readValue(
                    cfg.getReviewers(), new TypeReference<List<PrAutoReviewDtos.ReviewerItem>>() {});
            return list != null && list.stream().anyMatch(item -> item != null && actorId.equals(item.id()));
        } catch (JsonProcessingException e) {
            LOG.warn("反序列化审核者名单失败，配置 ID: {}", cfg.getId());
            return false;
        }
    }

    private PrAutoReviewDtos.WebhookReceiveResult recordRejected(String reasonCode, String reasonMsg) {
        LocalDateTime now = AgentTime.now();
        PrAutoReviewDelivery delivery = new PrAutoReviewDelivery();
        delivery.setId(AgentIds.next());
        delivery.setFirstReceivedAt(now);
        delivery.setLastReceivedAt(now);
        delivery.setReceiveCount(1);
        delivery.setDecisionState("REJECTED");
        delivery.setDecidedAt(now);
        delivery.setDecisionCode(reasonCode);
        delivery.setDecisionMessage(reasonMsg);
        delivery.setRevision(0);
        delivery.setCommentState("NONE");
        delivery.setCommentAttempts(0);
        delivery.setUpdatedAt(now);

        db.mapper(PrAutoReviewDeliveryMapper.class).insert(delivery);

        return toReceiveResult(delivery);
    }

    private static PrAutoReviewDtos.WebhookReceiveResult toReceiveResult(PrAutoReviewDelivery delivery) {
        return new PrAutoReviewDtos.WebhookReceiveResult(delivery.getId(), delivery.getDecisionState(),
                delivery.getDecisionCode(), delivery.getDecisionMessage(), delivery.getReceiveCount());
    }

    private static boolean verifySignature(String hostType, String secret, byte[] payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank() || secret == null || secret.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expectedHex = HexFormat.of().formatHex(mac.doFinal(payload));
            String actualHex = signatureHeader.trim();
            if ("github".equalsIgnoreCase(hostType)) {
                if (!actualHex.startsWith("sha256=")) {
                    return false;
                }
                actualHex = actualHex.substring("sha256=".length());
            }
            return MessageDigest.isEqual(
                    expectedHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                    actualHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    private static String desensitizePayload(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode copy = root.deepCopy();
        removeSensitiveFields(copy);
        return copy.toString();
    }

    private static void removeSensitiveFields(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String name = field.getKey().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
                if (SENSITIVE_FIELDS.contains(name) || name.endsWith("token") || name.endsWith("secret")
                        || name.endsWith("password") || name.endsWith("credential") || name.endsWith("signature")) {
                    fields.remove();
                } else {
                    removeSensitiveFields(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(PrAutoReviewService::removeSensitiveFields);
        }
    }

    private String safeStoredPayload(String snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return desensitizePayload(objectMapper.readTree(snapshot));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private record BoundRepository(String projectId, String instance, String fullName) {}

    private record ProjectBinding(String projectId, PrAutoReviewConfig config) {}

    private List<BoundRepository> configuredRepositories(String hostType) {
        List<AgentProject> projects = db.mapper(AgentProjectMapper.class)
                .selectList(new LambdaQueryWrapper<AgentProject>()
                        .select(AgentProject::getId, AgentProject::getRepoUrl));
        List<BoundRepository> repositories = new ArrayList<>();
        for (AgentProject project : projects) {
            try {
                GitRepoRef ref = GitRepoRef.parse(project.getRepoUrl());
                if (!hostType.equals(gitHostClients.forRepo(ref).pullRequestProvider())) {
                    continue;
                }
                String instance = "github".equals(hostType) ? ref.host().toLowerCase(Locale.ROOT)
                        : giteaInstance(ref);
                if (instance != null) {
                    repositories.add(new BoundRepository(project.getId(), instance, ref.owner() + "/" + ref.repo()));
                }
            } catch (BusinessException | IllegalArgumentException ignored) {
                // 历史项目可能没有可用的仓库宿主绑定。
            }
        }
        return repositories;
    }

    private String resolveInstance(String hostType, String requestedInstance, List<BoundRepository> repositories) {
        boolean github = "github".equals(hostType);
        Set<String> known = repositories.stream().map(BoundRepository::instance).collect(Collectors.toSet());
        if (github) {
            known.add("github.com");
        } else if (known.isEmpty()) {
            String configured = giteaApiInstance();
            if (configured != null) {
                known.add(configured);
            }
        }
        if (requestedInstance == null || requestedInstance.isBlank()) {
            return github ? "github.com" : known.size() == 1 ? known.iterator().next() : null;
        }
        String requested = github ? requestedInstance.strip().toLowerCase(Locale.ROOT)
                : normalizeGiteaRoot(requestedInstance);
        return known.contains(requested) ? requested : null;
    }

    private String giteaInstance(GitRepoRef ref) {
        String root = normalizeGiteaRoot(ref.baseUrl());
        return root == null ? giteaApiInstance() : root;
    }

    private String giteaApiInstance() {
        String apiBase = giteaProperties.normalizedApiBase();
        String suffix = "/api/v1";
        return apiBase != null && apiBase.endsWith(suffix)
                ? normalizeGiteaRoot(apiBase.substring(0, apiBase.length() - suffix.length())) : null;
    }

    private static String normalizeGiteaRoot(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int defaultPort = switch (scheme) {
                case "http" -> 80;
                case "https" -> 443;
                default -> -1;
            };
            if (defaultPort == -1) {
                return null;
            }
            int port = uri.getPort();
            String root = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 || port == defaultPort ? "" : ":" + port)
                    + uri.getRawPath().replaceFirst("/+$", "");
            return root.length() <= 500 ? root : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<ProjectBinding> resolveProjects(List<BoundRepository> repositories, String instance,
                                                 Long repoId, String repoFullName) {
        List<BoundRepository> candidates = repositories.stream().filter(repo -> instance.equals(repo.instance())).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, PrAutoReviewConfig> configs = new HashMap<>();
        db.mapper(PrAutoReviewConfigMapper.class)
                .selectList(new LambdaQueryWrapper<PrAutoReviewConfig>().in(PrAutoReviewConfig::getProjectId,
                        candidates.stream().map(BoundRepository::projectId).toList()))
                .forEach(config -> configs.put(config.getProjectId(), config));

        List<ProjectBinding> byId = new ArrayList<>();
        List<ProjectBinding> byName = new ArrayList<>();
        for (BoundRepository repo : candidates) {
            PrAutoReviewConfig config = configs.get(repo.projectId());
            Long configuredId = config == null ? null : config.getRepoId();
            if (configuredId != null) {
                if (configuredId.equals(repoId)) {
                    byId.add(new ProjectBinding(repo.projectId(), config));
                }
            } else if (repo.fullName().equalsIgnoreCase(repoFullName)) {
                byName.add(new ProjectBinding(repo.projectId(), config));
            }
        }
        return byId.isEmpty() ? byName : byId;
    }

    private String effectiveSecret(PrAutoReviewConfig config) {
        String projectSecret = config == null ? null : config.getWebhookSecret();
        return projectSecret == null || projectSecret.isBlank() ? globalWebhookSecret : projectSecret;
    }

    private static Long positiveId(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0 ? value.longValue() : null;
    }

    private AgentProject requireProject(String id) {
        if (id == null || id.isBlank()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目 ID 不能为空");
        }
        AgentProject p = db.mapper(AgentProjectMapper.class).selectById(id);
        if (p == null || (p.getIsDeleted() != null && p.getIsDeleted() == 1)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在: " + id);
        }
        return p;
    }

    private PrAutoReviewDtos.ConfigView toConfigView(PrAutoReviewConfig config, AgentProject project) {
        List<PrAutoReviewDtos.ReviewerItem> reviewers = List.of();
        if (config.getReviewers() != null && !config.getReviewers().isBlank()) {
            try {
                reviewers = objectMapper.readValue(config.getReviewers(), new TypeReference<List<PrAutoReviewDtos.ReviewerItem>>() {});
            } catch (Exception e) {
                LOG.warn("解析审核者 JSON 失败: {}", e.getMessage());
            }
        }
        String repoFullName = config.getRepoFullName();
        if (repoFullName == null || repoFullName.isBlank()) {
            repoFullName = defaultRepoFullName(project);
        }
        return new PrAutoReviewDtos.ConfigView(
                config.getId(),
                config.getProjectId(),
                config.getEnabled() != null && config.getEnabled() == 1,
                config.getTargetBranch(),
                config.getModelConfigId(),
                config.getPromptId(),
                config.getPromptVersionId(),
                config.getReviewBaseUrl(),
                config.getWebhookSecret() != null && !config.getWebhookSecret().isBlank(),
                reviewers,
                config.getBotActorId(),
                config.getBotLogin(),
                config.getRepoId(),
                repoFullName,
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private static String defaultRepoFullName(AgentProject project) {
        if (project == null || project.getRepoUrl() == null || project.getRepoUrl().isBlank()) {
            return null;
        }
        try {
            GitRepoRef ref = GitRepoRef.parse(project.getRepoUrl());
            return ref.owner() + "/" + ref.repo();
        } catch (Exception e) {
            return null;
        }
    }

    private PrAutoReviewDtos.DeliveryView toDeliveryView(PrAutoReviewDelivery d) {
        return new PrAutoReviewDtos.DeliveryView(
                d.getId(),
                d.getHostType(),
                d.getHostInstanceKey(),
                d.getDeliveryId(),
                d.getFirstReceivedAt(),
                d.getLastReceivedAt(),
                d.getReceiveCount() != null ? d.getReceiveCount() : 1,
                d.getEventName(),
                d.getEventType(),
                d.getEventAction(),
                d.getRepoId(),
                d.getRepoFullName(),
                d.getPrNumber(),
                d.getActorUserId(),
                d.getActorLogin(),
                d.getSourceCommentId(),
                d.getProjectId(),
                d.getTriggerKind(),
                d.getTargetBranch(),
                d.getDecisionState(),
                d.getDecidedAt(),
                d.getDecisionCode(),
                d.getDecisionMessage(),
                d.getBusinessKey(),
                d.getCanonicalRecordId(),
                d.getReviewTaskId(),
                d.getReviewUrl(),
                d.getCommentState(),
                d.getCommentId(),
                d.getUpdatedAt()
        );
    }

    private PrAutoReviewDtos.DeliveryDetailView toDeliveryDetailView(PrAutoReviewDelivery d) {
        return new PrAutoReviewDtos.DeliveryDetailView(
                d.getId(),
                d.getHostType(),
                d.getHostInstanceKey(),
                d.getDeliveryId(),
                d.getFirstReceivedAt(),
                d.getLastReceivedAt(),
                d.getReceiveCount() != null ? d.getReceiveCount() : 1,
                d.getEventName(),
                d.getEventType(),
                d.getEventAction(),
                d.getRepoId(),
                d.getRepoFullName(),
                d.getPrNumber(),
                d.getActorUserId(),
                d.getActorLogin(),
                d.getSourceCommentId(),
                safeStoredPayload(d.getPayloadSnapshot()),
                d.getProjectId(),
                d.getTriggerKind(),
                d.getTargetBranch(),
                d.getDecisionState(),
                d.getDecidedAt(),
                d.getDecisionCode(),
                d.getDecisionMessage(),
                d.getBusinessKey(),
                d.getCanonicalRecordId(),
                d.getTaskRequestSnapshot(),
                d.getSourceHead(),
                d.getTargetHead(),
                d.getMergeBase(),
                d.getTextCount(),
                d.getBinaryCount(),
                d.getUnreadableCount(),
                d.getReviewTaskId(),
                d.getReviewUrl(),
                d.getCommentActorId(),
                d.getCommentBody(),
                d.getCommentAttempts() != null ? d.getCommentAttempts() : 0,
                d.getCommentLastAttemptAt(),
                d.getCommentState(),
                d.getCommentId(),
                d.getCommentHttpStatus(),
                d.getCommentError(),
                d.getCommentCheckedAt(),
                d.getRevision() != null ? d.getRevision() : 0,
                d.getLeaseUntil(),
                d.getNextAttemptAt(),
                d.getUpdatedAt()
        );
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
