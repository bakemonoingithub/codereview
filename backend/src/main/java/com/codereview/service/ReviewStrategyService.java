package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.StrategyReq;
import com.codereview.entity.ModelConfig;
import com.codereview.entity.ReviewStrategy;
import com.codereview.mapper.ReviewStrategyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * 审查策略（M2 最小：列表 + 新建；仅支持 llm-review 分析器，params={modelConfigId}）
 */
@Service
public class ReviewStrategyService {

    private static final int ANALYZER_LLM_REVIEW = 1;

    private final ReviewStrategyMapper strategyMapper;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewStrategyService(ReviewStrategyMapper strategyMapper, ModelConfigService modelConfigService) {
        this.strategyMapper = strategyMapper;
        this.modelConfigService = modelConfigService;
    }

    public ReviewStrategy create(StrategyReq req) {
        if (req.name() == null || req.name().isBlank() || req.modelConfigId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        ModelConfig model = modelConfigService.getOrThrow(req.modelConfigId());
        ObjectNode params = objectMapper.createObjectNode();
        params.put("modelConfigId", model.getId().toString());
        ReviewStrategy s = new ReviewStrategy();
        s.setName(req.name());
        s.setAnalyzerType(ANALYZER_LLM_REVIEW);
        try {
            s.setParamsJson(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new IllegalStateException("序列化策略参数失败", e);
        }
        strategyMapper.insert(s);
        return s;
    }

    public Page<ReviewStrategy> list(long pageNum, long pageSize) {
        return strategyMapper.selectPage(new Page<>(pageNum, pageSize), null);
    }

    public ReviewStrategy getOrThrow(Long id) {
        ReviewStrategy s = strategyMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.STRATEGY_NOT_FOUND);
        }
        return s;
    }
}
