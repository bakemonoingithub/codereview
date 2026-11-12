package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.PrAutoReviewConfig;
import com.codereview.agent.entity.PrAutoReviewDelivery;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.PrAutoReviewConfigMapper;
import com.codereview.agent.mapper.PrAutoReviewDeliveryMapper;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PR 自动审查服务：项目配置管理、Webhook 接收、验签、查重与持久化（PRA-02）。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class PrAutoReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(PrAutoReviewService.class);
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie", "key", "apikey", "privatekey",
            "xhubsignature256", "credentials");

    private final AgentDatabase db;
    private final GitHostClientRegistry gitHostClients;
    private final ObjectMapper objectMapper;
    private final GiteaProperties giteaProperties;

    @Value("${git.webhook-secret:}")
    private String globalWebhookSecret;

    public PrAutoReviewService(AgentDatabase db, GitHostClientRegistry gitHostClients, ObjectMapper objectMapper,
                               GiteaProperties giteaProperties) {
        this.db = db;
        this.gitHostClients = gitHostClients;
        this.objectMapper = objectMapper;
        this.giteaProperties = giteaProperties;
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
                    null,
                    null,
                    null
            );
        }
        return toConfigView(config);
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

        return toConfigView(config);
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

        return db.inTransaction(() -> {
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
    }

    /** 查询某项目的投递处理记录列表。 */
    public PageResponse<PrAutoReviewDtos.DeliveryView> listDeliveries(String projectId,
                                                                      String eventName,
                                                                      String decisionState,
                                                                      String cursor,
                                                                      Integer limit,
                                                                      Integer pageNum,
                                                                      Integer pageSize) {
        requireProject(projectId);
        LambdaQueryWrapper<PrAutoReviewDelivery> wrapper = new LambdaQueryWrapper<PrAutoReviewDelivery>()
                .eq(PrAutoReviewDelivery::getProjectId, projectId);

        if (eventName != null && !eventName.isBlank()) {
            wrapper.eq(PrAutoReviewDelivery::getEventName, eventName.trim());
        }
        if (decisionState != null && !decisionState.isBlank()) {
            wrapper.eq(PrAutoReviewDelivery::getDecisionState, decisionState.trim());
        }

        PageResponse<PrAutoReviewDelivery> page = AgentPaging.query(
                db.mapper(PrAutoReviewDeliveryMapper.class),
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
        PrAutoReviewDelivery delivery = db.mapper(PrAutoReviewDeliveryMapper.class)
                .selectOne(new LambdaQueryWrapper<PrAutoReviewDelivery>()
                        .eq(PrAutoReviewDelivery::getProjectId, projectId)
                        .eq(PrAutoReviewDelivery::getId, deliveryId));

        if (delivery == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "未找到指定的投递处理记录");
        }
        return toDeliveryDetailView(delivery);
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
            if (!"created".equals(action) || !prComment) {
                skip(delivery, "ACTION_IGNORED", "仅处理新建的 PR 普通评论");
                return;
            }
            Long actorId = delivery.getActorUserId();
            if (actorId != null && actorId.equals(cfg.getBotActorId())) {
                skip(delivery, "BOT_COMMENT", "机器人自身发言，跳过回写触发");
                return;
            }

            if (!"/recheck".equals(root.path("comment").path("body").asText("").strip())) {
                skip(delivery, "COMMAND_MISMATCH", "评论正文未精确匹配 /recheck 命令");
                return;
            }
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

        if (pullRequest && (gitea ? "pull_request_sync".equals(eventType) : "synchronize".equals(action))) {
            skip(delivery, "ACTION_IGNORED", "追加提交后由审核者发起 /recheck 复审");
            return;
        }
        skip(delivery, "EVENT_NOT_SUPPORTED", "该 Webhook 事件未配置审查触发规则");
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

    private PrAutoReviewDtos.ConfigView toConfigView(PrAutoReviewConfig config) {
        List<PrAutoReviewDtos.ReviewerItem> reviewers = List.of();
        if (config.getReviewers() != null && !config.getReviewers().isBlank()) {
            try {
                reviewers = objectMapper.readValue(config.getReviewers(), new TypeReference<List<PrAutoReviewDtos.ReviewerItem>>() {});
            } catch (Exception e) {
                LOG.warn("解析审核者 JSON 失败: {}", e.getMessage());
            }
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
                config.getRepoFullName(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
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
}
