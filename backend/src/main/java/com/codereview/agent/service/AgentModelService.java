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
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.invocation.ModelSnapshot;
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
    private final ModelInvocationService invocation;
    private final ObjectMapper objectMapper;

    public AgentModelService(AgentDatabase db, ModelInvocationService invocation,
                             ObjectMapper objectMapper) {
        this.db = db;
        this.invocation = invocation;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ModelDtos.View> list(String q, String cursor, Integer limit) {
        return list(q, cursor, limit, null, null);
    }

    public PageResponse<ModelDtos.View> list(String q, String cursor, Integer limit, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<AgentModelConfig> wrapper = new LambdaQueryWrapper<>();
        if (q != null && !q.isBlank()) {
            wrapper.and(w -> w.like(AgentModelConfig::getName, q.trim())
                    .or().like(AgentModelConfig::getModelName, q.trim()));
        }
        PageResponse<AgentModelConfig> page = AgentPaging.query(
                db.mapper(AgentModelConfigMapper.class), wrapper, cursor, limit, pageNum, pageSize,
                AgentModelConfig::getCreatedAt, AgentModelConfig::getId);
        return page.withItems(page.items().stream().map(this::toView).toList());
    }

    public ModelDtos.View create(ModelDtos.Create req) {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(AgentIds.next());
        applyFields(model, req.name(), req.baseUrl(), req.modelName(), req.contextWindow(),
                req.maxOutput(), req.tokenizer(), req.protocol());
        model.setCredential(blankToNull(req.credential()));
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
        // 可空字段（tokenizer、窗口、输出）允许被清空，必须显式 set；实体更新会忽略 null。
        LambdaUpdateWrapper<AgentModelConfig> where = new LambdaUpdateWrapper<AgentModelConfig>()
                .eq(AgentModelConfig::getId, id)
                .eq(AgentModelConfig::getRevision, req.expectedRevision())
                .set(AgentModelConfig::getName, req.name().trim())
                .set(AgentModelConfig::getBaseUrl, req.baseUrl().trim())
                .set(AgentModelConfig::getModelName, req.modelName().trim())
                .set(AgentModelConfig::getContextWindow, req.contextWindow())
                .set(AgentModelConfig::getMaxOutput, req.maxOutput())
                .set(AgentModelConfig::getTokenizer, blankToNull(req.tokenizer()))
                .set(AgentModelConfig::getProtocol,
                        req.protocol() == null || req.protocol().isBlank() ? "OPENAI_CHAT" : req.protocol())
                .set(AgentModelConfig::getRevision, req.expectedRevision() + 1)
                .set(AgentModelConfig::getUpdatedAt, AgentTime.now());
        String credential = blankToNull(req.credential());
        if (credential != null) {
            where.set(AgentModelConfig::getCredential, credential);
        }
        int affected = db.mapper(AgentModelConfigMapper.class).update(null, where);
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
        // 验证与业务调用共用组装、计量、请求落盘和传输入口，不另建一套探针模板。
        ModelInvocationService.ValidationOutcome outcome =
                invocation.validate(ModelSnapshot.from(model));
        AgentModelValidation record = new AgentModelValidation();
        record.setId(AgentIds.next());
        record.setModelConfigId(model.getId());
        record.setConfigRevision(model.getRevision());
        record.setScope("CONNECTIVITY");
        record.setResult(outcome.passed() ? "PASSED" : "FAILED");
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("detail", outcome.detail());
        if (outcome.callId() != null) {
            detail.put("callId", outcome.callId());
        }
        record.setDetailJson(toJson(detail));
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
                model.getCredential() != null && !model.getCredential().isBlank(),
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
