package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.StrategyDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentReviewStrategy;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentReviewStrategyMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.codereview.agent.support.SpecialistTypes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 策略 v2 服务。
 *
 * <p>策略 = 专项类型 + 模型 + 可选通用规则提示词。引用按稳定 ID 校验：
 * 模型必须存在，提示词必须存在且用途为 {@code SPECIALIST}，避免把报告或 Agent 提示词
 * 悄悄当成审查规则使用。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentStrategyService {

    private final AgentDatabase db;

    public AgentStrategyService(AgentDatabase db) {
        this.db = db;
    }

    public PageResponse<StrategyDtos.View> list(String q, String specialistType, String cursor, Integer limit) {
        int size = AgentPaging.limit(limit);
        LambdaQueryWrapper<AgentReviewStrategy> wrapper = new LambdaQueryWrapper<>();
        if (q != null && !q.isBlank()) {
            wrapper.like(AgentReviewStrategy::getName, q.trim());
        }
        if (specialistType != null && !specialistType.isBlank()) {
            if (!SpecialistTypes.isValid(specialistType)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "专项类型只能是 ISSUE/PATTERN/COUPLING", Map.of("field", "specialistType"));
            }
            wrapper.eq(AgentReviewStrategy::getSpecialistType, specialistType);
        }
        AgentPaging.applyCursor(wrapper, cursor, AgentReviewStrategy::getCreatedAt, AgentReviewStrategy::getId);
        AgentPaging.applyOrder(wrapper, AgentReviewStrategy::getCreatedAt, AgentReviewStrategy::getId);
        wrapper.last("limit " + (size + 1));
        PageResponse<AgentReviewStrategy> page = AgentPaging.assemble(
                db.mapper(AgentReviewStrategyMapper.class).selectList(wrapper), size,
                AgentReviewStrategy::getCreatedAt, AgentReviewStrategy::getId);
        return new PageResponse<>(page.items().stream().map(AgentStrategyService::toView).toList(),
                page.nextCursor(), page.hasMore());
    }

    public StrategyDtos.View create(StrategyDtos.Create req) {
        requireModel(req.modelConfigId());
        requirePrompt(req.promptId());

        AgentReviewStrategy strategy = new AgentReviewStrategy();
        strategy.setId(AgentIds.next());
        strategy.setName(req.name().trim());
        strategy.setSpecialistType(req.specialistType());
        strategy.setModelConfigId(req.modelConfigId());
        strategy.setPromptId(req.promptId());
        strategy.setRevision(0);
        strategy.setCreatedAt(AgentTime.now());
        strategy.setUpdatedAt(AgentTime.now());
        strategy.setIsDeleted(0);
        db.mapper(AgentReviewStrategyMapper.class).insert(strategy);
        return toView(strategy);
    }

    public StrategyDtos.View update(String id, StrategyDtos.Update req) {
        AgentReviewStrategy current = require(id);
        if (current.getRevision() != req.expectedRevision()) {
            throw conflict(current.getRevision());
        }
        requireModel(req.modelConfigId());
        requirePrompt(req.promptId());

        AgentReviewStrategy patch = new AgentReviewStrategy();
        patch.setName(req.name().trim());
        patch.setSpecialistType(req.specialistType());
        patch.setModelConfigId(req.modelConfigId());
        patch.setPromptId(req.promptId());
        patch.setRevision(req.expectedRevision() + 1);
        patch.setUpdatedAt(AgentTime.now());
        // 允许把可选提示词清空：update 默认忽略 null，因此这里显式 set 成 null 需要 UpdateWrapper。
        LambdaUpdateWrapper<AgentReviewStrategy> where = new LambdaUpdateWrapper<AgentReviewStrategy>()
                .eq(AgentReviewStrategy::getId, id)
                .eq(AgentReviewStrategy::getRevision, req.expectedRevision())
                .set(AgentReviewStrategy::getName, patch.getName())
                .set(AgentReviewStrategy::getSpecialistType, patch.getSpecialistType())
                .set(AgentReviewStrategy::getModelConfigId, patch.getModelConfigId())
                .set(AgentReviewStrategy::getPromptId, patch.getPromptId())
                .set(AgentReviewStrategy::getRevision, patch.getRevision())
                .set(AgentReviewStrategy::getUpdatedAt, patch.getUpdatedAt());
        int affected = db.mapper(AgentReviewStrategyMapper.class).update(null, where);
        if (affected == 0) {
            throw conflict(require(id).getRevision());
        }
        return toView(require(id));
    }

    public StrategyDtos.View detail(String id) {
        return toView(require(id));
    }

    public void delete(String id) {
        AgentReviewStrategy strategy = require(id);
        db.mapper(AgentReviewStrategyMapper.class).deleteById(strategy.getId());
    }

    private AgentReviewStrategy require(String id) {
        AgentReviewStrategy strategy = db.mapper(AgentReviewStrategyMapper.class)
                .selectById(AgentIds.requireValid(id, "策略 ID"));
        if (strategy == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "策略不存在：" + id);
        }
        return strategy;
    }

    private void requireModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "策略必须关联模型",
                    Map.of("field", "modelConfigId"));
        }
        AgentModelConfig model = db.mapper(AgentModelConfigMapper.class)
                .selectById(AgentIds.requireValid(modelId, "模型 ID"));
        if (model == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "关联的模型不存在：" + modelId,
                    Map.of("field", "modelConfigId"));
        }
    }

    private void requirePrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) {
            return;
        }
        AgentPrompt prompt = db.mapper(AgentPromptMapper.class)
                .selectById(AgentIds.requireValid(promptId, "提示词 ID"));
        if (prompt == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "关联的提示词不存在：" + promptId,
                    Map.of("field", "promptId"));
        }
        if (!"SPECIALIST".equals(prompt.getPurpose())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "该提示词用途为 " + prompt.getPurpose() + "，不能作为专项规则",
                    Map.of("field", "promptId"));
        }
    }

    private static ApiException conflict(int currentRevision) {
        return new ApiException(ErrorCode.REVISION_CONFLICT, "策略已被其他操作修改，请刷新后重试",
                Map.of("currentRevision", currentRevision));
    }

    private static StrategyDtos.View toView(AgentReviewStrategy strategy) {
        return new StrategyDtos.View(strategy.getId(), strategy.getName(), strategy.getSpecialistType(),
                strategy.getModelConfigId(), strategy.getPromptId(), strategy.getRevision(),
                strategy.getCreatedAt(), strategy.getUpdatedAt());
    }
}
