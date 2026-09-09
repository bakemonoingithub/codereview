package com.codereview.config;

import com.codereview.entity.ModelConfig;
import com.codereview.llm.LlmProperties;
import com.codereview.mapper.ModelConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动初始化：model_config 为空时，用 yml 的 deepseek 配置 seed 一条默认模型，
 * 便于立即建策略/触发审查，无需手工先建模型。
 */
@Slf4j
@Component
public class DataInitializer implements ApplicationRunner {

    private final ModelConfigMapper modelConfigMapper;
    private final LlmProperties llmProperties;

    public DataInitializer(ModelConfigMapper modelConfigMapper, LlmProperties llmProperties) {
        this.modelConfigMapper = modelConfigMapper;
        this.llmProperties = llmProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = modelConfigMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        ModelConfig m = new ModelConfig();
        m.setName("DeepSeek(默认)");
        m.setBaseUrl(llmProperties.getBaseUrl());
        m.setToken(llmProperties.getApiKey());
        m.setModelName(llmProperties.getModel());
        m.setStatus(1);
        modelConfigMapper.insert(m);
        log.info("已初始化默认模型配置 modelConfigId={} model={}", m.getId(), m.getModelName());
    }
}
