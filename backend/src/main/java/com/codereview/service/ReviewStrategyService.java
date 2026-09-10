package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.StrategyReq;
import com.codereview.entity.ReviewStrategy;
import com.codereview.mapper.ReviewStrategyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审查策略（M4 完整版）：四种分析器（llm-review/coupling/design-pattern/api-review）+ 按类型参数校验 + 编辑/删除。
 * 参数：
 *   llm-review/coupling/design-pattern → {modelConfigId, threshold?, promptId?}
 *   api-review → {apiUrl, resultUrl, queryUrl?, token?}
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
        if (req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        int analyzerType = req.analyzerType() == null ? 1 : req.analyzerType();
        if (analyzerType < 1 || analyzerType > 4) {
            throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
        }
        Map<String, Object> params = req.params() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(req.params());
        validateParams(analyzerType, params);
        ReviewStrategy s = new ReviewStrategy();
        s.setName(req.name());
        s.setAnalyzerType(analyzerType);
        s.setParamsJson(writeParams(params));
        strategyMapper.insert(s);
        return s;
    }

    public ReviewStrategy update(Long id, StrategyReq req) {
        ReviewStrategy s = getOrThrow(id);
        if (req.name() != null && !req.name().isBlank()) {
            s.setName(req.name());
        }
        if (req.analyzerType() != null) {
            int at = req.analyzerType();
            if (at < 1 || at > 4) {
                throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
            }
            s.setAnalyzerType(at);
        }
        if (req.params() != null) {
            Map<String, Object> params = new LinkedHashMap<>(req.params());
            validateParams(s.getAnalyzerType(), params);
            s.setParamsJson(writeParams(params));
        }
        strategyMapper.updateById(s);
        return s;
    }

    public void delete(Long id) {
        getOrThrow(id);
        strategyMapper.deleteById(id);
    }

    public Page<ReviewStrategy> list(long pageNum, long pageSize, String keyword, Integer analyzerType) {
        LambdaQueryWrapper<ReviewStrategy> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.like(ReviewStrategy::getName, keyword);
        }
        if (analyzerType != null) {
            w.eq(ReviewStrategy::getAnalyzerType, analyzerType);
        }
        w.orderByDesc(ReviewStrategy::getUpdatedAt);
        return strategyMapper.selectPage(new Page<>(pageNum, pageSize), w);
    }

    public ReviewStrategy getOrThrow(Long id) {
        ReviewStrategy s = strategyMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.STRATEGY_NOT_FOUND);
        }
        return s;
    }

    private void validateParams(int analyzerType, Map<String, Object> params) {
        if (analyzerType == 4) {
            if (blank(params.get("apiUrl")) || blank(params.get("resultUrl"))) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "api-review 策略需配置调用API与结果展示地址");
            }
            return;
        }
        Object modelId = params.get("modelConfigId");
        if (blank(modelId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择模型");
        }
        modelConfigService.getOrThrow(Long.parseLong(String.valueOf(modelId)));
    }

    private String writeParams(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("序列化策略参数失败", e);
        }
    }

    private boolean blank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }
}
