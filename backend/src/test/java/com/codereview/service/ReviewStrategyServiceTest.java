package com.codereview.service;

import com.codereview.common.AnalyzerTypes;
import com.codereview.common.BusinessException;
import com.codereview.dto.StrategyReq;
import com.codereview.dto.StrategyResp;
import com.codereview.entity.ModelConfig;
import com.codereview.entity.ReviewStrategy;
import com.codereview.mapper.ReviewStrategyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        StrategyResp created = service.create(new StrategyReq("变更审查", AnalyzerTypes.DIFF_REVIEW, llmParams()));

        assertEquals(AnalyzerTypes.DIFF_REVIEW, created.analyzerType());
        assertTrue(created.paramsJson().contains("modelConfigId"));
    }

    @Test
    void acceptsEveryDeclaredType() {
        for (int type = AnalyzerTypes.MIN; type <= AnalyzerTypes.MAX; type++) {
            Map<String, Object> params = type == AnalyzerTypes.API_REVIEW
                    ? Map.of("apiUrl", "http://x", "resultUrl", "http://y")
                    : llmParams();
            assertEquals(type, service.create(new StrategyReq("策略" + type, type, params)).analyzerType());
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
                service.create(new StrategyReq("无窗口", AnalyzerTypes.DIFF_REVIEW, llmParams())).analyzerType());
    }

    // ------------------------------------------------------------------
    // 凭据只写不读（E4）
    // ------------------------------------------------------------------

    private static Map<String, Object> apiReviewParams(Object token) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("apiUrl", "http://sonar/api");
        params.put("resultUrl", "http://sonar/dashboard");
        if (token != null) {
            params.put("token", token);
        }
        return params;
    }

    private ReviewStrategy existingApiReview(String paramsJson) {
        ReviewStrategy existing = new ReviewStrategy();
        existing.setId(1L);
        existing.setName("Sonar 审查");
        existing.setAnalyzerType(AnalyzerTypes.API_REVIEW);
        existing.setParamsJson(paramsJson);
        when(strategyMapper.selectById(1L)).thenReturn(existing);
        return existing;
    }

    @Test
    void createResponseNeverCarriesToken() {
        StrategyResp resp = service.create(
                new StrategyReq("Sonar", AnalyzerTypes.API_REVIEW, apiReviewParams("sonar-secret-token")));

        assertFalse(resp.paramsJson().contains("sonar-secret-token"), "响应里不能出现 token 明文");
        assertFalse(resp.paramsJson().contains("token"), "token 键本身也应被摘掉");
        assertTrue(resp.hasToken(), "但界面要能知道'已配置 token'");
        assertTrue(resp.paramsJson().contains("apiUrl"), "其余参数仍要回传给编辑表单");
    }

    @Test
    void editWithoutTokenKeepsStoredToken() {
        // 关键回归：token 不再回传浏览器 ⇒ 前端编辑时拿不到明文 ⇒ 提交的 params 里没有 token。
        // 若按入参整体覆盖，一次普通编辑就会把已存的 Sonar token 静默抹掉。
        ReviewStrategy existing = existingApiReview(
                "{\"apiUrl\":\"http://sonar/api\",\"resultUrl\":\"http://sonar/d\",\"token\":\"stored-token\"}");

        StrategyResp resp = service.update(1L, new StrategyReq("改名", null, apiReviewParams(null)));

        assertTrue(existing.getParamsJson().contains("stored-token"), "未重填时应保留原 token");
        assertTrue(resp.hasToken());
        assertFalse(resp.paramsJson().contains("stored-token"), "响应仍然不能带明文");
    }

    @Test
    void editWithNewTokenReplacesStoredToken() {
        ReviewStrategy existing = existingApiReview(
                "{\"apiUrl\":\"http://sonar/api\",\"resultUrl\":\"http://sonar/d\",\"token\":\"stored-token\"}");

        service.update(1L, new StrategyReq("改名", null, apiReviewParams("brand-new-token")));

        assertTrue(existing.getParamsJson().contains("brand-new-token"));
        assertFalse(existing.getParamsJson().contains("stored-token"), "旧 token 应被替换");
    }

    @Test
    void editWithClearTokenRemovesIt() {
        ReviewStrategy existing = existingApiReview(
                "{\"apiUrl\":\"http://sonar/api\",\"resultUrl\":\"http://sonar/d\",\"token\":\"stored-token\"}");
        Map<String, Object> params = apiReviewParams(null);
        params.put("clearToken", true);

        StrategyResp resp = service.update(1L, new StrategyReq("改名", null, params));

        assertFalse(existing.getParamsJson().contains("token"), "显式清除后不应残留 token");
        assertFalse(resp.hasToken());
        assertFalse(existing.getParamsJson().contains("clearToken"), "clearToken 是控制位，不应入库");
    }

    @Test
    void corruptStoredParamsDoesNotBreakResponse() {
        existingApiReview("{坏掉的 json");

        StrategyResp resp = service.update(1L, new StrategyReq("改名", null, apiReviewParams(null)));

        assertFalse(resp.hasToken(), "脏数据退化为无 token，而不是让接口抛错");
        assertTrue(resp.paramsJson().startsWith("{"));
    }

    /**
     * 名称判重（V5 移除 {@code uk_name} 后由应用层承接）。
     * <p>
     * 既有用例没有 stub {@code selectCount}，Mockito 默认返回 null —— 判重实现必须
     * 对 null 安全，否则这些用例会被新逻辑弄挂。
     */
    @Test
    void rejectsDuplicateNameOnCreate() {
        when(strategyMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(new StrategyReq("重名", AnalyzerTypes.LLM_REVIEW, llmParams())));

        assertEquals(1005, e.getCode());
        verify(strategyMapper, never()).insert(any(ReviewStrategy.class));
    }

    @Test
    void rejectsRenameThatCollidesWithAnother() {
        ReviewStrategy existing = new ReviewStrategy();
        existing.setId(1L);
        existing.setName("原名");
        existing.setAnalyzerType(AnalyzerTypes.LLM_REVIEW);
        existing.setParamsJson("{\"modelConfigId\":\"1\"}");
        when(strategyMapper.selectById(1L)).thenReturn(existing);
        when(strategyMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(1L, new StrategyReq("已占用", null, llmParams())));

        assertEquals(1005, e.getCode());
        verify(strategyMapper, never()).updateById(any(ReviewStrategy.class));
    }

    @Test
    void allowsKeepingOwnNameOnUpdate() {
        ReviewStrategy existing = new ReviewStrategy();
        existing.setId(1L);
        existing.setName("原名");
        existing.setAnalyzerType(AnalyzerTypes.LLM_REVIEW);
        existing.setParamsJson("{\"modelConfigId\":\"1\"}");
        when(strategyMapper.selectById(1L)).thenReturn(existing);
        // 排除自身后不应命中任何记录
        when(strategyMapper.selectCount(any())).thenReturn(0L);

        StrategyResp resp = service.update(1L, new StrategyReq("原名", null, llmParams()));

        assertEquals("原名", resp.name());
    }
}
