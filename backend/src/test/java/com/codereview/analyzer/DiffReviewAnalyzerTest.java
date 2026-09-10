package com.codereview.analyzer;

import com.codereview.config.ReviewProperties;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.llm.LlmClient;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** diff-review 分析器：护栏（缺提交/无变更/单元上限）+ 端到端单元流水线（意图注入、行号锚定）。 */
class DiffReviewAnalyzerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private GitHostClient git;
    private LlmClient llm;
    private ThreadPoolTaskExecutor executor;
    private DiffReviewAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        git = mock(GitHostClient.class);
        llm = mock(LlmClient.class);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.initialize();
        analyzer = new DiffReviewAnalyzer(git, llm, executor, new ReviewProperties());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void typeIsFive() {
        assertEquals(5, analyzer.type());
    }

    // ---------------- 护栏 ----------------

    @Test
    void missingCommitShaFails() {
        AnalyzeOutcome outcome = analyzer.analyze(ctx(null, List.of()));

        assertEquals(ReviewStatus.FAILED, outcome.status());
        assertTrue(outcome.result().path("error").asText().contains("未指定提交"),
                outcome.result().path("error").asText());
    }

    @Test
    void commitDetailFailureIsReported() {
        when(git.commitDetail(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("远端 500"));

        AnalyzeOutcome outcome = analyzer.analyze(ctx("abc1234", List.of()));

        assertEquals(ReviewStatus.FAILED, outcome.status());
        assertTrue(outcome.result().path("error").asText().contains("远端 500"));
    }

    @Test
    void commitWithoutChangedFilesFails() {
        when(git.commitDetail(any(), any(), any(), any(), any()))
                .thenReturn(detail(List.of()));

        AnalyzeOutcome outcome = analyzer.analyze(ctx("abc1234", List.of()));

        assertEquals(ReviewStatus.FAILED, outcome.status());
        assertTrue(outcome.result().path("error").asText().contains("没有可审查的变更文件"));
    }

    @Test
    void unitCapIsEnforced() {
        List<ChangedFile> files = new ArrayList<>();
        for (int i = 0; i <= DiffReviewAnalyzer.MAX_UNITS; i++) {
            files.add(new ChangedFile("db/f" + i + ".sql", null, "modified", 1, 1, 2, "@@ -1 +1 @@\n-a\n+b\n"));
        }
        when(git.commitDetail(any(), any(), any(), any(), any())).thenReturn(detail(files));

        AnalyzeOutcome outcome = analyzer.analyze(ctx("abc1234", List.of()));

        assertEquals(ReviewStatus.FAILED, outcome.status());
        assertTrue(outcome.result().path("error").asText().contains("超过上限"),
                outcome.result().path("error").asText());
    }

    // ---------------- 端到端：变更意图注入 + 行级评论 ----------------

    @Test
    void happyPathInjectsIntentBlockAndReturnsLineAnchoredIssues() {
        String javaFile = "public class A {\n"
                + "    public void m() {\n"
                + "        int a = 1;\n"
                + "        int b = 2;\n"
                + "    }\n"
                + "}\n";
        String patch = "@@ -1,5 +1,6 @@\n"
                + " public class A {\n"
                + "     public void m() {\n"
                + "         int a = 1;\n"
                + "+        int b = 2;\n"
                + "     }\n"
                + " }\n";
        when(git.commitDetail(any(), any(), any(), any(), any())).thenReturn(new CommitDetail(
                "abc1234", List.of("p1"), "修复订单为空时的 NPE", "张三", "2026-09-03T21:00:00Z",
                30, 10, 40, false,
                List.of(new ChangedFile("src/A.java", null, "modified", 5, 1, 6, patch))));
        when(git.rawFile(any(), any(), any(), any(), any(), any())).thenReturn(javaFile);
        when(llm.chatJson(any(), any(), any(), any(), any())).thenReturn(
                "{\"intentVerdict\":\"部分符合\",\"intentNote\":\"只处理了一处空值\","
                        + "\"issues\":[{\"severity\":\"MAJOR\",\"category\":\"缺陷\",\"newLine\":4,"
                        + "\"title\":\"空值未处理\",\"description\":\"d\",\"suggestion\":\"s\"}],"
                        + "\"summary\":\"有遗漏\"}");

        AnalyzeOutcome outcome = analyzer.analyze(ctx("abc1234", List.of("src/A.java")));

        assertEquals(ReviewStatus.SUCCESS, outcome.status(), outcome.result().toString());
        JsonNode root = outcome.result();
        assertEquals("abc1234", root.path("commit").path("sha").asText());
        assertEquals("p1", root.path("commit").path("baseSha").asText());
        assertEquals(1, root.path("units").size());

        JsonNode unit = root.path("units").get(0);
        assertEquals("success", unit.path("status").asText());
        assertEquals("diff-method", unit.path("unit").path("kind").asText());
        assertEquals("m", unit.path("unit").path("name").asText());
        assertEquals("modified", unit.path("changeType").asText());
        assertEquals("部分符合", unit.path("intentVerdict").asText());
        assertEquals(4, unit.path("issues").get(0).path("newLine").asInt());
        assertEquals(4, unit.path("issues").get(0).path("line").asInt(), "line 保留以兼容旧前端");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).chatJson(any(), any(), any(), any(), prompt.capture());
        String sent = prompt.getValue();
        assertTrue(sent.contains("修复订单为空时的 NPE"), "变更意图（commit message）应注入提示词");
        assertTrue(sent.contains("张三"), "作者应注入提示词");
        assertTrue(sent.contains("int b = 2;"), "变更代码应注入提示词");
        assertTrue(sent.contains("[变更意图]"), sent.substring(0, Math.min(200, sent.length())));
    }

    @Test
    void fileWithoutPatchFallsBackToFullFileAndDefaultsVerdict() {
        when(git.commitDetail(any(), any(), any(), any(), any())).thenReturn(new CommitDetail(
                "abc1234", List.of("p1"), "m", "a", "d", 1, 1, 2, false,
                List.of(new ChangedFile("logo.png", null, "modified", null, null, null, null))));
        when(git.rawFile(any(), any(), any(), any(), any(), any())).thenReturn("binary-ish");
        when(llm.chatJson(any(), any(), any(), any(), any())).thenReturn("{\"issues\":[],\"summary\":\"ok\"}");

        AnalyzeOutcome outcome = analyzer.analyze(ctx("abc1234", List.of()));

        assertEquals(ReviewStatus.SUCCESS, outcome.status());
        JsonNode unit = outcome.result().path("units").get(0);
        assertEquals("full-file", unit.path("unit").path("kind").asText());
        assertEquals("无法判断", unit.path("intentVerdict").asText(), "缺 intentVerdict 时归一化为无法判断");
    }

    @Test
    void boundPromptIsInjectedAsFocusRulesNotReplacingTemplate() {
        String javaFile = "public class A {\n"
                + "    public void m() {\n"
                + "        int a = 1;\n"
                + "        int b = 2;\n"
                + "    }\n"
                + "}\n";
        String patch = "@@ -1,5 +1,6 @@\n"
                + " public class A {\n"
                + "     public void m() {\n"
                + "         int a = 1;\n"
                + "+        int b = 2;\n"
                + "     }\n"
                + " }\n";
        when(git.commitDetail(any(), any(), any(), any(), any())).thenReturn(new CommitDetail(
                "abc1234", List.of("p1"), "msg", "author", "date", 1, 0, 1, false,
                List.of(new ChangedFile("src/A.java", null, "modified", 1, 0, 1, patch))));
        when(git.rawFile(any(), any(), any(), any(), any(), any())).thenReturn(javaFile);
        when(llm.chatJson(any(), any(), any(), any(), any())).thenReturn("{\"issues\":[],\"summary\":\"ok\"}");

        analyzer.analyze(ctxWithPrompt("禁止使用魔法值，必须判空"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).chatJson(any(), any(), any(), any(), prompt.capture());
        String sent = prompt.getValue();
        assertTrue(sent.contains("[本次审查的关注点与规则]"), "策略绑定的提示词应作为关注点注入");
        assertTrue(sent.contains("禁止使用魔法值，必须判空"));
        assertTrue(sent.contains("intentVerdict"), "方法模板不应被用户提示词替代");
    }

    // ---------------- 意图结论归一化 ----------------

    @Test
    void verdictNormalizationCoversFourStates() {
        assertEquals("符合", DiffReviewAnalyzer.normalizeVerdict("符合"));
        assertEquals("部分符合", DiffReviewAnalyzer.normalizeVerdict("部分符合"));
        assertEquals("不符", DiffReviewAnalyzer.normalizeVerdict("不符"));
        assertEquals("无法判断", DiffReviewAnalyzer.normalizeVerdict("说不清"));
        assertEquals("无法判断", DiffReviewAnalyzer.normalizeVerdict(null));
        assertEquals("无法判断", DiffReviewAnalyzer.normalizeVerdict(""));
        assertEquals("部分符合", DiffReviewAnalyzer.normalizeVerdict("部分符合预期"));
        assertEquals("不符", DiffReviewAnalyzer.normalizeVerdict("不符合"));
    }

    // ---------------- 辅助 ----------------

    private static CommitDetail detail(List<ChangedFile> files) {
        return new CommitDetail("abc1234", List.of("p1"), "msg", "author", "date", 1, 1, 2, false, files);
    }

    private AnalysisContext ctx(String commitSha, List<String> scope) {
        return ctx(commitSha, scope, null);
    }

    private AnalysisContext ctxWithPrompt(String customPrompt) {
        return ctx("abc1234", List.of(), customPrompt);
    }

    private AnalysisContext ctx(String commitSha, List<String> scope, String customPrompt) {
        ReviewRecord record = new ReviewRecord();
        record.setBranch("main");
        record.setCommitSha(commitSha);
        Project project = new Project();
        project.setGiteaUrl("https://github.com/o/r");
        project.setCredential("token");
        project.setCredentialType(1);
        return new AnalysisContext(record, project, GitRepoRef.parse("https://github.com/o/r"), scope,
                "https://api.deepseek.com", "key", "deepseek-chat",
                mapper.createObjectNode(), customPrompt, false, pct -> {
        });
    }
}
