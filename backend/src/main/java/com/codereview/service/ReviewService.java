package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.ReviewRecordResp;
import com.codereview.dto.ReviewTriggerReq;
import com.codereview.entity.ModelConfig;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.entity.ReviewStrategy;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.mapper.ModelConfigMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.mapper.ReviewStrategyMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ReviewService {

    private static final int ANALYZER_LLM_REVIEW = 1;

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final ReviewStrategyMapper strategyMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final GitHostClient gitHostClient;
    private final ReviewExecutor reviewExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                         ReviewStrategyMapper strategyMapper, ModelConfigMapper modelConfigMapper,
                         GitHostClient gitHostClient, ReviewExecutor reviewExecutor) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.strategyMapper = strategyMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.gitHostClient = gitHostClient;
        this.reviewExecutor = reviewExecutor;
    }

    public ReviewRecord trigger(Long projectId, ReviewTriggerReq req) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        if (req.strategyId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择审查策略");
        }
        if (req.scope() == null || req.scope().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择审查范围");
        }
        ReviewStrategy strategy = strategyMapper.selectById(req.strategyId());
        if (strategy == null) {
            throw new BusinessException(ResultCode.STRATEGY_NOT_FOUND);
        }
        if (strategy.getAnalyzerType() == null || strategy.getAnalyzerType() != ANALYZER_LLM_REVIEW) {
            throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
        }
        ModelConfig model = resolveModelConfig(strategy);
        String commitSha = resolveCommitSha(p, req.branch());

        ReviewRecord record = new ReviewRecord();
        record.setProjectId(projectId);
        record.setStrategyId(strategy.getId());
        record.setStrategySnapshotJson(buildSnapshot(strategy, model));
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
            return gitHostClient.headCommitSha(p.getCredential(), ref.owner(), ref.repo(), branch);
        } catch (Exception e) {
            log.warn("解析 HEAD commit sha 失败，降级留空: {}", e.getMessage());
            return null;
        }
    }

    private String buildSnapshot(ReviewStrategy strategy, ModelConfig model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("strategyId", strategy.getId().toString());
        root.put("strategyName", strategy.getName());
        root.put("analyzerType", strategy.getAnalyzerType());
        ObjectNode m = root.putObject("model");
        m.put("baseUrl", model.getBaseUrl());
        m.put("apiKey", model.getToken());
        m.put("modelName", model.getModelName());
        return root.toString();
    }
}
