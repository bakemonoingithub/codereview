package com.codereview.analyzer;

import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitRepoRef;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Consumer;

/** 单次分析上下文：范围、模型配置、策略参数、进度回调、任务级 deadline。 */
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
        Consumer<Integer> progress,
        Deadline deadline) {

    /** 兼容旧调用（不设任务级超时）。 */
    public AnalysisContext(ReviewRecord record, Project project, GitRepoRef ref, List<String> scope,
                           String baseUrl, String apiKey, String modelName, JsonNode params,
                           String customPrompt, boolean mergeFiles, Consumer<Integer> progress) {
        this(record, project, ref, scope, baseUrl, apiKey, modelName, params, customPrompt, mergeFiles,
                progress, Deadline.none());
    }

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

    /**
     * 任务级 deadline：一次审查由多个单元组成、每个单元还会重试，**单次 HTTP 读超时封不住总时长**
     * （4 并发 × 每单元最多 4 次 × 300s 读超时，极端情况下远超验收指标 8 的 1 小时）。
     *
     * <p>分析器在**提交每个单元前**自检：超时就把剩余单元标记为"未执行"并停止提交。
     * 选协作式而不是线程池级硬中断，是因为单元并发在分析器内部（`reviewUnitExecutor`），
     * 从外面 Future.get(timeout) 只会丢弃已提交单元的结果、留下仍在跑的线程。
     * 代价是：已经在飞的调用最多再等一个读超时（≤300s）。
     */
    public record Deadline(long atMillis, int minutes) {

        /** 不设超时（旧调用与单测用）。 */
        public static Deadline none() {
            return new Deadline(Long.MAX_VALUE, 0);
        }

        public boolean exceeded() {
            return System.currentTimeMillis() > atMillis;
        }

        /** 写进单元的失败原因（带配置的分钟数，便于现场判断是不是超时）。 */
        public String reason() {
            return minutes > 0
                    ? "任务级超时（超过 " + minutes + " 分钟），该单元未执行"
                    : "任务级超时，该单元未执行";
        }
    }
}
