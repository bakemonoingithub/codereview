package com.codereview.analyzer;

import com.codereview.config.ReviewProperties;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.git.TestGitHostClients;
import com.codereview.llm.LlmClient;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * llm-review 的**任务级超时**（backlog ⑥「整个审查任务没有总超时」）。
 *
 * <p>单次 HTTP 调用有读超时（300s），但一次审查由多个单元组成、每单元还会重试 ——
 * 总时长只有 deadline 能封住。这里用"已经过期的 deadline"做**确定性**验证：
 * 不需要 sleep，也不依赖真实时钟推进。
 */
class LlmReviewAnalyzerDeadlineTest {

    private GitHostClient git;
    private LlmClient llm;
    private ThreadPoolTaskExecutor executor;
    private LlmReviewAnalyzer analyzer;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        git = mock(GitHostClient.class);
        llm = mock(LlmClient.class);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(16);
        executor.initialize();
        analyzer = new LlmReviewAnalyzer(TestGitHostClients.routingTo(git), llm, executor, new ReviewProperties());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private AnalysisContext context(List<String> scope, boolean mergeFiles) {
        // deadline 已过期：等价于"这条记录已经跑过了配置的任务级时长上限"
        return context(scope, mergeFiles, new AnalysisContext.Deadline(System.currentTimeMillis() - 1, 50));
    }

    private AnalysisContext context(List<String> scope, boolean mergeFiles, AnalysisContext.Deadline deadline) {
        ReviewRecord record = new ReviewRecord();
        record.setBranch("main");
        record.setCommitSha("abc1234");
        Project project = new Project();
        project.setGiteaUrl("https://github.com/o/r");
        project.setCredential("token");
        project.setCredentialType(1);
        return new AnalysisContext(record, project, GitRepoRef.parse("https://github.com/o/r"), scope,
                "https://api.deepseek.com", "key", "deepseek-chat",
                mapper.createObjectNode(), null, mergeFiles, pct -> {
        }, deadline);
    }

    @Test
    void expiredDeadlineMarksEveryUnitUnrunAndNeverCallsTheModel() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn("public class A {}\n");

        AnalyzeOutcome outcome = analyzer.analyze(context(List.of("src/A.java"), false));

        assertEquals(ReviewStatus.FAILED, outcome.status(), "一个单元都没执行 → 失败");
        JsonNode unit = outcome.result().path("units").get(0);
        assertTrue(unit.path("error").asText().contains("任务级超时"), unit.toString());
        verify(llm, never()).chatJson(any(), any(), any(), any(), any());
        verify(llm, never()).chat(any(), any(), any(), any(), any());
    }

    @Test
    void mergedPathStopsBeforeCallingTheModel() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn("public class A {}\n");

        AnalyzeOutcome outcome = analyzer.analyze(context(List.of("src/A.java", "src/B.java"), true));

        assertEquals(ReviewStatus.FAILED, outcome.status());
        JsonNode unit = outcome.result().path("units").get(0);
        assertTrue(unit.path("error").asText().contains("任务级超时"), unit.toString());
        verify(llm, never()).chat(any(), any(), any(), any(), any());
    }

    @Test
    void noDeadlineStillRunsTheModel() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn("public class A {}\n");
        when(llm.chatJson(any(), any(), any(), any(), any()))
                .thenReturn("{\"issues\":[],\"summary\":\"ok\"}");

        AnalyzeOutcome outcome = analyzer.analyze(
                context(List.of("src/A.java"), false, AnalysisContext.Deadline.none()));

        assertEquals(ReviewStatus.SUCCESS, outcome.status(), outcome.result().toString());
        verify(llm).chatJson(any(), any(), any(), any(), any());
    }
}
