package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
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
        ModelConfig m = new ModelConfig();
        m.setName(req.name());
        m.setBaseUrl(req.baseUrl());
        m.setToken(req.token());
        m.setModelName(req.modelName());
        m.setStatus(0); // 未验证
        modelConfigMapper.insert(m);
        return verifyConnectivity(m);
    }

    public ModelConfig update(Long id, ModelConfigReq req) {
        ModelConfig m = getOrThrow(id);
        if (req.name() != null && !req.name().isBlank()) {
            m.setName(req.name());
        }
        m.setBaseUrl(req.baseUrl());
        m.setToken(req.token());
        m.setModelName(req.modelName());
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
        return modelConfigMapper.selectPage(new Page<>(pageNum, pageSize), null);
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
            m.setStatus(2); // 验证失败
        }
        modelConfigMapper.updateById(m);
        return m;
    }
}
