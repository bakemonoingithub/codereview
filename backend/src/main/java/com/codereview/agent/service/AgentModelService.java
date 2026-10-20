package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ModelDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentModelValidation;
import com.codereview.agent.entity.AgentReviewStrategy;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentModelValidationMapper;
import com.codereview.agent.mapper.AgentReviewStrategyMapper;
import com.codereview.agent.model.ModelProbe;
import com.codereview.agent.model.ModelProbeOutcome;
import com.codereview.agent.model.ModelProbeRequest;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 模型 v2 服务。
 *
 * <p>验证记录绑定 {@code revision}：配置一改，旧验证自动变成 EXPIRED，不需要额外清理任务；
 * 删除被策略引用的模型返回 409，不级联破坏引用。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentModelService {

    private final AgentDatabase db;
    private final ModelProbe probe;
    private final ObjectMapper objectMapper;

    public AgentModelService(AgentDatabase db, ModelProbe probe, ObjectMapper objectMapper) {
        this.db = db;
        this.probe = probe;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ModelDtos.View> list(String q, String cursor, Integer limit) {
        int size = AgentPaging.limit(limit);
        LambdaQueryWrapper<AgentModelConfig> wrapper = new LambdaQueryWrapper<>();
        if (q != null && !q.isBlank()) {
            wrapper.and(w -> w.like(AgentModelConfig::getName, q.trim())
                    .or().like(AgentModelConfig::getModelName, q.trim()));
        }
        AgentPaging.applyCursor(wrapper, cursor, AgentModelConfig::getCreatedAt, AgentModelConfig::getId);
        AgentPaging.applyOrder(wrapper, AgentModelConfig::getCreatedAt, AgentModelConfig::getId);
        wrapper.last("limit " + (size + 1));
        PageResponse<AgentModelConfig> page = AgentPaging.assemble(
                db.mapper(AgentModelConfigMapper.class).selectList(wrapper), size,
                AgentModelConfig::getCreatedAt, AgentModelConfig::getId);
        return new PageResponse<>(page.items().stream().map(this::toView).toList(),
                page.nextCursor(), page.hasMore());
    }

    public ModelDtos.View create(ModelDtos.Create req) {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(AgentIds.next());
        applyFields(model, req.name(), req.baseUrl(), req.modelName(), req.contextWindow(),
                req.maxOutput(), req.tokenizer(), req.protocol());
        model.setCredentialRef(blankToNull(req.credential()));
        model.setRevision(0);
        model.setCreatedAt(AgentTime.now());
        model.setUpdatedAt(AgentTime.now());
        model.setIsDeleted(0);
        db.mapper(AgentModelConfigMapper.class).insert(model);
        return toView(model);
    }

    public ModelDtos.View update(String id, ModelDtos.Update req) {
        AgentModelConfig current = require(id);
        if (current.getRevision() != req.expectedRevision()) {
            throw conflict(current.getRevision());
        }
        AgentModelConfig patch = new AgentModelConfig();
        applyFields(patch, req.name(), req.baseUrl(), req.modelName(), req.contextWindow(),
                req.maxOutput(), req.tokenizer(), req.protocol());
        String credential = blankToNull(req.credential());
        if (credential != null) {
            patch.setCredentialRef(credential);
        }
        patch.setRevision(req.expectedRevision() + 1);
        patch.setUpdatedAt(AgentTime.now());
        int affected = db.mapper(AgentModelConfigMapper.class).update(patch,
                new LambdaUpdateWrapper<AgentModelConfig>()
                        .eq(AgentModelConfig::getId, id)
                        .eq(AgentModelConfig::getRevision, req.expectedRevision()));
        if (affected == 0) {
            throw conflict(require(id).getRevision());
        }
        return toView(require(id));
    }

    public ModelDtos.View detail(String id) {
        return toView(require(id));
    }

    public void delete(String id) {
        AgentModelConfig model = require(id);
        Long used = db.mapper(AgentReviewStrategyMapper.class).selectCount(
                new LambdaQueryWrapper<AgentReviewStrategy>()
                        .eq(AgentReviewStrategy::getModelConfigId, model.getId()));
        if (used != null && used > 0) {
            throw new ApiException(ErrorCode.CONFIG_IN_USE,
                    "该模型仍被 " + used + " 个策略引用，请先调整关联", Map.of("strategies", used));
        }
        db.mapper(AgentModelConfigMapper.class).deleteById(model.getId());
    }

    /**
     * 连通验证：结果写入 {@code model_validation}，绑定当前 revision。
     *
     * <p>验证失败不抛 4xx —— 这是一次成功的"验证动作"，结果本身是 FAILED；
     * 只有模型不存在才是错误。
     */
    public ModelDtos.ValidationResult validate(String id) {
        AgentModelConfig model = require(id);
        ModelProbeOutcome outcome = probe.probe(new ModelProbeRequest(
                model.getBaseUrl(), model.getModelName(), model.getCredentialRef()));
        AgentModelValidation record = new AgentModelValidation();
        record.setId(AgentIds.next());
        record.setModelConfigId(model.getId());
        record.setConfigRevision(model.getRevision());
        record.setScope("CONNECTIVITY");
        record.setResult(outcome.passed() ? "PASSED" : "FAILED");
        record.setDetailJson(toJson(Map.of("detail", outcome.detail())));
        record.setCreatedAt(AgentTime.now());
        db.mapper(AgentModelValidationMapper.class).insert(record);
        return new ModelDtos.ValidationResult(record.getResult(), record.getConfigRevision(),
                record.getScope(), outcome.detail(), record.getCreatedAt());
    }

    private AgentModelConfig require(String id) {
        AgentModelConfig model = db.mapper(AgentModelConfigMapper.class)
                .selectById(AgentIds.requireValid(id, "模型 ID"));
        if (model == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "模型不存在：" + id);
        }
        return model;
    }

    private ModelDtos.View toView(AgentModelConfig model) {
        AgentModelValidation latest = latestValidation(model.getId());
        return new ModelDtos.View(model.getId(), model.getName(), model.getBaseUrl(), model.getModelName(),
                model.getCredentialRef() != null && !model.getCredentialRef().isBlank(),
                model.getContextWindow(), model.getMaxOutput(), model.getTokenizer(), model.getProtocol(),
                validationStatus(model, latest), model.getRevision(), model.getCreatedAt(), model.getUpdatedAt());
    }

    private AgentModelValidation latestValidation(String modelId) {
        List<AgentModelValidation> rows = db.mapper(AgentModelValidationMapper.class).selectList(
                new LambdaQueryWrapper<AgentModelValidation>()
                        .eq(AgentModelValidation::getModelConfigId, modelId)
                        .orderByDesc(AgentModelValidation::getCreatedAt)
                        .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String validationStatus(AgentModelConfig model, AgentModelValidation latest) {
        if (latest == null) {
            return "NONE";
        }
        if (!latest.getConfigRevision().equals(model.getRevision())) {
            return "EXPIRED";
        }
        return latest.getResult();
    }

    private static void applyFields(AgentModelConfig model, String name, String baseUrl, String modelName,
                                    Integer contextWindow, Integer maxOutput, String tokenizer, String protocol) {
        model.setName(name.trim());
        model.setBaseUrl(baseUrl.trim());
        model.setModelName(modelName.trim());
        model.setContextWindow(contextWindow);
        model.setMaxOutput(maxOutput);
        model.setTokenizer(blankToNull(tokenizer));
        model.setProtocol(protocol == null || protocol.isBlank() ? "OPENAI_CHAT" : protocol);
    }

    private static ApiException conflict(int currentRevision) {
        return new ApiException(ErrorCode.REVISION_CONFLICT, "模型已被其他操作修改，请刷新后重试",
                Map.of("currentRevision", currentRevision));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
