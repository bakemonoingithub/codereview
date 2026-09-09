package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.ModelConfigReq;
import com.codereview.entity.ModelConfig;
import com.codereview.mapper.ModelConfigMapper;
import org.springframework.stereotype.Service;

/**
 * 模型接入配置（M2 最小：列表 + 新建；连通验证与加密留给 M4）
 */
@Service
public class ModelConfigService {

    private final ModelConfigMapper modelConfigMapper;

    public ModelConfigService(ModelConfigMapper modelConfigMapper) {
        this.modelConfigMapper = modelConfigMapper;
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
        m.setStatus(1); // M2 暂不做连通验证（M4 补），直接标记为可用
        modelConfigMapper.insert(m);
        return m;
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
}
