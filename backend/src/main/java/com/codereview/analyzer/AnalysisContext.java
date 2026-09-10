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
        boolean mergeFiles,
        Consumer<Integer> progress) {

    public String branch() {
        return record.getBranch();
    }

    /**
     * 取内容的引用：<b>优先锁定 commit sha</b>——分支会移动，按分支取内容会与审查记录里
     * 记下的提交不一致；sha 缺失（历史记录或解析失败）时才退回分支名。
     */
    public String contentRef() {
        String sha = record.getCommitSha();
        return (sha == null || sha.isBlank()) ? branch() : sha;
    }
}
