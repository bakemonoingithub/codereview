package com.codereview.analyzer;

import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitRepoRef;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Consumer;

/** 单次分析上下文：范围、模型配置、策略参数、进度回调。 */
public record AnalysisContext(
        ReviewRecord record,
        Project project,
        GitRepoRef ref,
        List<String> scope,
        String baseUrl,
        String apiKey,
        String modelName,
        JsonNode params,
        String customPrompt,
        Consumer<Integer> progress) {

    public String branch() {
        return record.getBranch();
    }
}
