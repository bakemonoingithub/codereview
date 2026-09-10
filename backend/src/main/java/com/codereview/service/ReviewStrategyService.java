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
 * 审查策略（M3：列表 + 新建；支持 llm-review/coupling/design-pattern 三种分析器）
 */
@Service
public class ReviewStrategyService {

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
        int analyzerType = req.analyzerType() == null ? 1 : req.analyzerType();
        if (analyzerType < 1 || analyzerType > 3) {
            throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
        }
        ModelConfig model = modelConfigService.getOrThrow(req.modelConfigId());
        ObjectNode params = objectMapper.createObjectNode();
        params.put("modelConfigId", model.getId().toString());
        if (analyzerType == 2 && req.threshold() != null) {
            params.put("threshold", req.threshold());
        }
        ReviewStrategy s = new ReviewStrategy();
        s.setName(req.name());
        s.setAnalyzerType(analyzerType);
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
