package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.PageLimits;
import com.codereview.common.ResultCode;
import com.codereview.dto.ModelConfigReq;
import com.codereview.entity.ModelConfig;
import com.codereview.llm.LlmClient;
import com.codereview.mapper.ModelConfigMapper;
import org.springframework.stereotype.Service;

/**
 * 模型接入配置（M4 完整版）：新建/编辑/删除 + 连通性验证（保存时发最小请求，回显成功/失败）。
 */
@Service
public class ModelConfigService {

    private final ModelConfigMapper modelConfigMapper;
    private final LlmClient llmClient;

    public ModelConfigService(ModelConfigMapper modelConfigMapper, LlmClient llmClient) {
        this.modelConfigMapper = modelConfigMapper;
        this.llmClient = llmClient;
    }

    public ModelConfig create(ModelConfigReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        ensureNameAvailable(req.name(), null);
        ModelConfig m = new ModelConfig();
        m.setName(req.name());
        m.setBaseUrl(req.baseUrl());
        m.setToken(req.token());
        m.setModelName(req.modelName());
        m.setStatus(0); // 未验证
        modelConfigMapper.insert(m);
        return verifyConnectivity(m);
    }

    /**
     * 名称在**未删除**的模型中必须唯一。
     *
     * <p>原先由数据库唯一索引 {@code uk_name} 保证，但唯一索引 + 逻辑删除会让**已删除的记录
     * 继续占用名称** —— 删掉后用同名重建必然失败。V5 移除该索引后，判重下移到这里
     * （MySQL 不支持"仅对未删除行唯一"的部分唯一索引）。
     *
     * <p>判重依赖 MyBatis-Plus 逻辑删除自动补 {@code is_deleted = 0}：已删除记录不参与，
     * 这正是"删除后可重建"成立的前提。
     *
     * <p>{@code excludeId} 用于编辑场景排除自身 —— 否则"只改地址不改名"会被自己判为重复。
     */
    private void ensureNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<ModelConfig> w = new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getName, name);
        if (excludeId != null) {
            w.ne(ModelConfig::getId, excludeId);
        }
        Long count = modelConfigMapper.selectCount(w);
        // 判空是为了兼容单测里未 stub 的 mapper（Mockito 默认返回 null）
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "模型名称已存在：" + name);
        }
    }

    public ModelConfig update(Long id, ModelConfigReq req) {
        ModelConfig m = getOrThrow(id);
        if (req.name() != null && !req.name().isBlank()) {
            m.setName(req.name());
        }
        ensureNameAvailable(m.getName(), m.getId());
        m.setBaseUrl(req.baseUrl());
        m.setModelName(req.modelName());
        if (Boolean.TRUE.equals(req.clearToken())) {
            m.setToken(null);
        } else if (req.token() != null && !req.token().isBlank()) {
            m.setToken(req.token());
        }
        // 否则 token 留空：保留原值（不覆盖）
        modelConfigMapper.updateById(m);
        return verifyConnectivity(m);
    }

    public void delete(Long id) {
        getOrThrow(id);
        modelConfigMapper.deleteById(id);
    }

    public ModelConfig verify(Long id) {
        return verifyConnectivity(getOrThrow(id));
    }

    public Page<ModelConfig> list(long pageNum, long pageSize) {
        return modelConfigMapper.selectPage(PageLimits.page(pageNum, pageSize), null);
    }

    public ModelConfig getOrThrow(Long id) {
        ModelConfig m = modelConfigMapper.selectById(id);
        if (m == null) {
            throw new BusinessException(ResultCode.MODEL_NOT_FOUND);
        }
        return m;
    }

    private ModelConfig verifyConnectivity(ModelConfig m) {
        try {
            llmClient.ping(m.getBaseUrl(), m.getToken(), m.getModelName());
            m.setStatus(1); // 验证成功
        } catch (Exception e) {
            // 失败仍要落 status=2（列表上能看到"验证失败"），但**必须报错**：
            // 原先无论成功失败都返回 200 + status=2，前端 await 正常 resolve、
            // 先弹一条绿色"验证完成"，刷新后才看到红色"验证失败"，且全程没有失败原因。
            m.setStatus(2);
            modelConfigMapper.updateById(m);
            throw new BusinessException(ResultCode.MODEL_VERIFY_FAILED.getCode(),
                    "连通验证失败：" + rootReason(e));
        }
        modelConfigMapper.updateById(m);
        return m;
    }

    /** 取最内层原因：RestClient 的异常外层往往只有一句笼统的 4xx/5xx 描述。 */
    private static String rootReason(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current.getCause() != null && current.getCause() != current && depth < 10; depth++) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return (message == null || message.isBlank()) ? current.getClass().getSimpleName() : message;
    }
}
