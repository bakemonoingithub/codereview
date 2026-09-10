package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.ReviewRecordResp;
import com.codereview.dto.ReviewTriggerReq;
import com.codereview.entity.ModelConfig;
import com.codereview.entity.Project;
import com.codereview.entity.Prompt;
import com.codereview.entity.PromptVersion;
import com.codereview.entity.ReviewRecord;
import com.codereview.entity.ReviewStrategy;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.mapper.ModelConfigMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.mapper.ReviewStrategyMapper;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ReviewService {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final ReviewStrategyMapper strategyMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final GitHostClient gitHostClient;
    private final ReviewExecutor reviewExecutor;
    private final PromptMapper promptMapper;
    private final PromptVersionMapper promptVersionMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                         ReviewStrategyMapper strategyMapper, ModelConfigMapper modelConfigMapper,
                         GitHostClient gitHostClient, ReviewExecutor reviewExecutor,
                         PromptMapper promptMapper, PromptVersionMapper promptVersionMapper) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.strategyMapper = strategyMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.gitHostClient = gitHostClient;
        this.reviewExecutor = reviewExecutor;
        this.promptMapper = promptMapper;
        this.promptVersionMapper = promptVersionMapper;
    }

    public ReviewRecord trigger(Long projectId, ReviewTriggerReq req) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        if (req.strategyId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择审查策略");
        }
        ReviewStrategy strategy = strategyMapper.selectById(req.strategyId());
        if (strategy == null) {
            throw new BusinessException(ResultCode.STRATEGY_NOT_FOUND);
        }
        int analyzerType = strategy.getAnalyzerType() == null ? 0 : strategy.getAnalyzerType();
        if (analyzerType < 1 || analyzerType > 5) {
            throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
        }
        boolean diffReview = analyzerType == 5;
        // diff 审查的范围由提交的变更文件决定，允许为空（为空即审全部变更文件）
        if (analyzerType != 4 && !diffReview && (req.scope() == null || req.scope().isEmpty())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择审查范围");
        }
        ModelConfig model = analyzerType == 4 ? null : resolveModelConfig(strategy);
        String commitSha;
        if (analyzerType == 4) {
            commitSha = null;
        } else if (diffReview) {
            // 必须锚定到具体提交：不接受缺省，否则会静默审到分支最新代码
            if (req.commitSha() == null || req.commitSha().isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                        "diff 审查必须指定提交（commitSha）");
            }
            commitSha = req.commitSha().trim();
        } else {
            commitSha = resolveCommitSha(p, req.branch());
        }

        ReviewRecord record = new ReviewRecord();
        record.setProjectId(projectId);
        record.setStrategyId(strategy.getId());
        record.setStrategySnapshotJson(buildSnapshot(strategy, model, Boolean.TRUE.equals(req.mergeFiles())));
        record.setBranch(req.branch());
        record.setCommitSha(commitSha);
        try {
            record.setScopeJson(objectMapper.writeValueAsString(req.scope()));
        } catch (Exception e) {
            throw new IllegalStateException("序列化 scope 失败", e);
        }
        record.setStatus(0);
        record.setProgress(0);
        reviewRecordMapper.insert(record);
        reviewExecutor.execute(record.getId());
        return record;
    }

    public void retry(Long reviewId) {
        ReviewRecord r = getOrThrow(reviewId);
        if (r.getStatus() == null || r.getStatus() < 2) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "审查未结束，无法重审");
        }
        // 同步置为执行中，避免前端轮询竞态；成功单元结果仍保留在 result_json 中，仅失败部分重跑
        r.setStatus(ReviewStatus.RUNNING);
        r.setProgress(0);
        reviewRecordMapper.updateById(r);
        reviewExecutor.retry(reviewId);
    }

    public ReviewRecordResp detail(Long reviewId) {
        ReviewRecord r = getOrThrow(reviewId);
        return new ReviewRecordResp(r.getId(), r.getProjectId(), r.getStrategyId(), r.getBranch(), r.getCommitSha(),
                r.getScopeJson(), r.getStatus(), r.getProgress(), r.getResultJson(),
                r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt());
    }

    public Page<ReviewRecord> list(Long projectId, long pageNum, long pageSize) {
        return reviewRecordMapper.selectPage(new Page<>(pageNum, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewRecord>()
                        .eq(ReviewRecord::getProjectId, projectId)
                        .orderByDesc(ReviewRecord::getCreatedAt));
    }

    private ReviewRecord getOrThrow(Long reviewId) {
        ReviewRecord r = reviewRecordMapper.selectById(reviewId);
        if (r == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }
        return r;
    }

    private ModelConfig resolveModelConfig(ReviewStrategy strategy) {
        try {
            JsonNode params = objectMapper.readTree(strategy.getParamsJson());
            long modelId = Long.parseLong(params.path("modelConfigId").asText());
            ModelConfig m = modelConfigMapper.selectById(modelId);
            if (m == null) {
                throw new BusinessException(ResultCode.MODEL_NOT_FOUND);
            }
            return m;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "策略参数解析失败: " + e.getMessage());
        }
    }

    private String resolveCommitSha(Project p, String branch) {
        try {
            GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
            return gitHostClient.headCommitSha(p.getCredential(), p.getCredentialType(), ref.owner(), ref.repo(), branch);
        } catch (Exception e) {
            log.warn("解析 HEAD commit sha 失败，降级留空: {}", e.getMessage());
            return null;
        }
    }

    private String buildSnapshot(ReviewStrategy strategy, ModelConfig model, boolean mergeFiles) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("strategyId", strategy.getId().toString());
        root.put("strategyName", strategy.getName());
        root.put("analyzerType", strategy.getAnalyzerType());
        root.put("mergeFiles", mergeFiles);
        if (model != null) {
            ObjectNode m = root.putObject("model");
            m.put("baseUrl", model.getBaseUrl());
            m.put("apiKey", model.getToken());
            m.put("modelName", model.getModelName());
        }
        try {
            JsonNode params = objectMapper.readTree(
                    strategy.getParamsJson() == null || strategy.getParamsJson().isBlank() ? "{}" : strategy.getParamsJson());
            root.set("params", params);
            String prompt = resolvePromptContent(params);
            if (prompt != null) {
                root.put("customPrompt", prompt);
            }
        } catch (Exception e) {
            throw new IllegalStateException("序列化策略快照失败", e);
        }
        return root.toString();
    }

    /** 从策略参数解析提示词 → 当前版本内容（关注点规则），注入快照供分析器使用。 */
    private String resolvePromptContent(JsonNode params) {
        String pid = params.path("promptId").asText(null);
        if (pid == null || pid.isBlank()) {
            pid = params.path("promptVersionId").asText(null);
        }
        if (pid == null || pid.isBlank()) {
            return null;
        }
        try {
            Prompt p = promptMapper.selectById(Long.parseLong(pid));
            if (p == null || p.getCurrentVersionId() == null) {
                return null;
            }
            PromptVersion v = promptVersionMapper.selectById(p.getCurrentVersionId());
            return v == null ? null : v.getContent();
        } catch (Exception e) {
            return null;
        }
    }
}
