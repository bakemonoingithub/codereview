package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.AnalyzerTypes;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.StrategyReq;
import com.codereview.dto.StrategyResp;
import com.codereview.entity.ReviewStrategy;
import com.codereview.mapper.ReviewStrategyMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审查策略：五种分析器（llm-review/coupling/design-pattern/api-review/diff-review）+ 按类型参数校验 + 编辑/删除。
 * 参数：
 *   llm-review/coupling/design-pattern/diff-review → {modelConfigId, threshold?, promptId?, methodWindowLines?}
 *   api-review → {apiUrl, resultUrl, queryUrl?, token?}
 *
 * <p>**凭据处理**：api-review 的 token 只写不读（响应里被摘掉，只留 hasToken 标记），
 * 且编辑时"入参不带 token"表示沿用原值，避免一次普通编辑把已存的 Sonar token 抹掉。
 */
@Service
public class ReviewStrategyService {

    /** 只写不读的凭据键：请求可带，响应必摘 */
    private static final String TOKEN_KEY = "token";
    /** 请求里置 true 表示显式清除凭据 */
    private static final String CLEAR_TOKEN_KEY = "clearToken";

    private final ReviewStrategyMapper strategyMapper;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewStrategyService(ReviewStrategyMapper strategyMapper, ModelConfigService modelConfigService) {
        this.strategyMapper = strategyMapper;
        this.modelConfigService = modelConfigService;
    }

    public StrategyResp create(StrategyReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        int analyzerType = req.analyzerType() == null ? AnalyzerTypes.LLM_REVIEW : req.analyzerType();
        if (!AnalyzerTypes.isValid(analyzerType)) {
            throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
        }
        Map<String, Object> params = req.params() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(req.params());
        params.remove(CLEAR_TOKEN_KEY); // 不是策略参数，不入库
        validateParams(analyzerType, params);
        ensureNameAvailable(req.name(), null);
        ReviewStrategy s = new ReviewStrategy();
        s.setName(req.name());
        s.setAnalyzerType(analyzerType);
        s.setParamsJson(writeParams(params));
        strategyMapper.insert(s);
        return toResp(s);
    }

    /**
     * 名称在**未删除**的策略中必须唯一。
     *
     * <p>原先由数据库唯一索引 {@code uk_name} 保证，但唯一索引 + 逻辑删除会让**已删除的记录
     * 继续占用名称** —— 删掉后用同名重建必然失败。V5 移除该索引后，判重下移到这里
     * （MySQL 不支持"仅对未删除行唯一"的部分唯一索引）。
     *
     * <p>判重依赖 MyBatis-Plus 逻辑删除自动补 {@code is_deleted = 0}：已删除记录不参与，
     * 这正是"删除后可重建"成立的前提。
     *
     * <p>{@code excludeId} 用于编辑场景排除自身 —— 否则"只改参数不改名"会被自己判为重复。
     */
    private void ensureNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<ReviewStrategy> w =
                new LambdaQueryWrapper<ReviewStrategy>().eq(ReviewStrategy::getName, name);
        if (excludeId != null) {
            w.ne(ReviewStrategy::getId, excludeId);
        }
        Long count = strategyMapper.selectCount(w);
        // 判空是为了兼容单测里未 stub 的 mapper（Mockito 默认返回 null）
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "策略名称已存在：" + name);
        }
    }

    public StrategyResp update(Long id, StrategyReq req) {
        ReviewStrategy s = getOrThrow(id);
        if (req.name() != null && !req.name().isBlank()) {
            s.setName(req.name());
        }
        ensureNameAvailable(s.getName(), s.getId());
        if (req.analyzerType() != null) {
            int at = req.analyzerType();
            if (!AnalyzerTypes.isValid(at)) {
                throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
            }
            s.setAnalyzerType(at);
        }
        if (req.params() != null) {
            Map<String, Object> params = mergePreservingToken(s, new LinkedHashMap<>(req.params()));
            validateParams(s.getAnalyzerType(), params);
            s.setParamsJson(writeParams(params));
        }
        strategyMapper.updateById(s);
        return toResp(s);
    }

    public void delete(Long id) {
        getOrThrow(id);
        strategyMapper.deleteById(id);
    }

    public Page<StrategyResp> list(long pageNum, long pageSize, String keyword, Integer analyzerType) {
        LambdaQueryWrapper<ReviewStrategy> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.like(ReviewStrategy::getName, keyword);
        }
        if (analyzerType != null) {
            w.eq(ReviewStrategy::getAnalyzerType, analyzerType);
        }
        w.orderByDesc(ReviewStrategy::getUpdatedAt);
        Page<ReviewStrategy> page = strategyMapper.selectPage(new Page<>(pageNum, pageSize), w);
        Page<StrategyResp> rows = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        rows.setRecords(page.getRecords().stream().map(this::toResp).toList());
        return rows;
    }

    public ReviewStrategy getOrThrow(Long id) {
        ReviewStrategy s = strategyMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.STRATEGY_NOT_FOUND);
        }
        return s;
    }

    /**
     * 实体 → 响应：摘掉凭据，只留 {@code hasToken}。
     *
     * <p>不能整体隐藏 {@code paramsJson} —— 界面编辑要用 apiUrl/threshold 等参数。
     */
    private StrategyResp toResp(ReviewStrategy s) {
        Map<String, Object> params = readParams(s.getParamsJson());
        boolean hasToken = !blank(params.get(TOKEN_KEY));
        params.remove(TOKEN_KEY);
        params.remove(CLEAR_TOKEN_KEY);
        return new StrategyResp(s.getId(), s.getName(), s.getAnalyzerType(), writeParams(params), hasToken,
                s.getCreatedAt(), s.getUpdatedAt());
    }

    /**
     * 合并策略参数，重点是 token 的三态：
     * <ul>
     *   <li>入参带了 token → 覆盖（用户重填了）；</li>
     *   <li>入参带了 {@code clearToken: true} → 清除；</li>
     *   <li>两者都没有 → 沿用已存的值。</li>
     * </ul>
     *
     * <p>第三态是必须的：token 已不回传浏览器，前端拿不到明文，不能要求它每次编辑都重填；
     * 若按入参整体覆盖，一次普通编辑就会静默抹掉 Sonar token，而验收指标 2/3 正依赖它。
     */
    private Map<String, Object> mergePreservingToken(ReviewStrategy s, Map<String, Object> incoming) {
        Map<String, Object> stored = readParams(s.getParamsJson());
        Object clearFlag = incoming.remove(CLEAR_TOKEN_KEY);
        boolean clear = clearFlag instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(clearFlag));

        if (clear) {
            incoming.remove(TOKEN_KEY);
            return incoming;
        }
        if (blank(incoming.get(TOKEN_KEY))) {
            incoming.remove(TOKEN_KEY);
            Object storedToken = stored.get(TOKEN_KEY);
            if (!blank(storedToken)) {
                incoming.put(TOKEN_KEY, storedToken);
            }
        }
        return incoming;
    }

    private Map<String, Object> readParams(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            // 存量脏数据不该让整个列表接口挂掉：退化为空参数（hasToken 也就为 false）
            return new LinkedHashMap<>();
        }
    }

    private void validateParams(int analyzerType, Map<String, Object> params) {
        if (analyzerType == AnalyzerTypes.API_REVIEW) {
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

        if (analyzerType == AnalyzerTypes.DIFF_REVIEW && !blank(params.get("methodWindowLines"))) {
            try {
                if (Integer.parseInt(String.valueOf(params.get("methodWindowLines"))) <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "方法体窗口必须是正整数");
            }
        }
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
