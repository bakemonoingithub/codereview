package com.codereview.service;

import com.codereview.common.AnalyzerTypes;
import com.codereview.common.BusinessException;
import com.codereview.dto.StrategyReq;
import com.codereview.entity.ModelConfig;
import com.codereview.entity.ReviewStrategy;
import com.codereview.mapper.ReviewStrategyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略创建的类型校验。
 * <p>
 * 回归用例：新增 diff-review(type=5) 时，「策略创建」处的上限漏改仍写死 4，
 * 导致建不出该类型策略并报「分析器类型不支持」。
 */
class ReviewStrategyServiceTest {

    private ReviewStrategyMapper strategyMapper;
    private ReviewStrategyService service;

    @BeforeEach
    void setUp() {
        strategyMapper = mock(ReviewStrategyMapper.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        when(modelConfigService.getOrThrow(any())).thenReturn(new ModelConfig());
        service = new ReviewStrategyService(strategyMapper, modelConfigService);
    }

    private static Map<String, Object> llmParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("modelConfigId", "1");
        return params;
    }

    @Test
    void acceptsDiffReviewType() {
        ReviewStrategy created = service.create(new StrategyReq("变更审查", AnalyzerTypes.DIFF_REVIEW, llmParams()));

        assertEquals(AnalyzerTypes.DIFF_REVIEW, created.getAnalyzerType());
        assertTrue(created.getParamsJson().contains("modelConfigId"));
    }

    @Test
    void acceptsEveryDeclaredType() {
        for (int type = AnalyzerTypes.MIN; type <= AnalyzerTypes.MAX; type++) {
            Map<String, Object> params = type == AnalyzerTypes.API_REVIEW
                    ? Map.of("apiUrl", "http://x", "resultUrl", "http://y")
                    : llmParams();
            assertEquals(type, service.create(new StrategyReq("策略" + type, type, params)).getAnalyzerType());
        }
    }

    @Test
    void rejectsTypeBeyondMax() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(new StrategyReq("越界", AnalyzerTypes.MAX + 1, llmParams())));
        assertEquals(4002, e.getCode());
    }

    @Test
    void rejectsUnknownTypeOnUpdate() {
        ReviewStrategy existing = new ReviewStrategy();
        existing.setId(1L);
        existing.setAnalyzerType(AnalyzerTypes.LLM_REVIEW);
        when(strategyMapper.selectById(1L)).thenReturn(existing);

        assertThrows(BusinessException.class,
                () -> service.update(1L, new StrategyReq("改名", 99, null)));
    }

    @Test
    void diffReviewWindowMustBePositive() {
        Map<String, Object> params = llmParams();
        params.put("methodWindowLines", 0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(new StrategyReq("窗口非法", AnalyzerTypes.DIFF_REVIEW, params)));
        assertTrue(e.getMessage().contains("正整数"), e.getMessage());
    }

    @Test
    void diffReviewWindowIsOptional() {
        assertEquals(AnalyzerTypes.DIFF_REVIEW,
                service.create(new StrategyReq("无窗口", AnalyzerTypes.DIFF_REVIEW, llmParams())).getAnalyzerType());
    }
}
