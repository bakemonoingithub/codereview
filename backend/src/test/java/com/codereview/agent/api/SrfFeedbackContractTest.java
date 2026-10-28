package com.codereview.agent.api;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.execution.TaskProperties;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.*;
import com.codereview.agent.execution.TaskDispatchService;
import com.codereview.agent.mapper.*;
import com.codereview.agent.report.ReportFactsBuilder;
import com.codereview.agent.report.ReportGenerationService;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.service.*;
import com.codereview.agent.support.AgentIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 三类专项共用一份前后端契约样本。真实控制器与服务，持久层为有状态替身，不连接数据库。 */
class SrfFeedbackContractTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, AgentFeedback> feedback = new LinkedHashMap<>();
    private final Map<String, AgentReport> reports = new LinkedHashMap<>();
    private final List<AgentReportSource> sources = new ArrayList<>();
    private MockMvc mvc;

    static Stream<JsonNode> cases() throws Exception {
        try (var input = SrfFeedbackContractTest.class.getResourceAsStream("/contracts/srf-result-feedback.json")) {
            assertNotNull(input);
            return StreamSupport.stream(JSON.readTree(input).path("cases").spliterator(), false).toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void resultFeedbackStatisticsAndFrozenReportShareStableIdentities(JsonNode sample) throws Exception {
        configure(sample);
        String kind = sample.path("kind").asText();
        String taskId = sample.path("taskId").asText();
        String resultId = sample.path("resultView").path("id").asText();
        String resultUrl = "/api/v2/review-tasks/" + taskId + "/targets/" + sample.path("targetId").asText() + "/result";
        assertEquals(sample.path("resultView"), response(get(resultUrl), 200));
        // 没有新评价时不得把历史整份结果的 100% 当作条目准确率。
        String countField = "ISSUE".equals(kind) ? "issues" : "PATTERN".equals(kind) ? "patterns" : "couplings";
        JsonNode initial = response(get("/api/v2/review-tasks/" + taskId + "/statistics"), 200).path(countField);
        assertEquals(4, initial.path("unassessed").asInt());
        assertTrue(initial.path("accuracyPercent").isNull());

        for (JsonNode expected : sample.path("feedbacks")) {
            String url = "/api/v2/feedback/" + kind + "/" + expected.path("subjectId").asText();
            ObjectNode request = JSON.createObjectNode();
            request.set("assessment", expected.path("assessment"));
            request.set("comment", expected.path("comment"));
            request.put("expectedRevision", 0);
            response(put(url).contentType(MediaType.APPLICATION_JSON).content(request.toString()), 200);
            ObjectNode loaded = (ObjectNode) response(get(url), 200);
            loaded.putNull("updatedAt");
            assertEquals(expected, loaded);
        }
        assertEquals(sample.path("statistics"), response(get("/api/v2/review-tasks/" + taskId + "/statistics"), 200));
        assertEquals(sample.path("resultView"), response(get(resultUrl), 200), "反馈不得改写分析正文或条目身份");
        JsonNode candidates = response(get("/api/v2/reports/candidates"), 200).path("items");
        assertEquals(1, candidates.size());
        if (!"ISSUE".equals(kind)) assertEquals(sample.path("statistics").path(countField), candidates.get(0).path("itemFeedback"));

        JsonNode frozen = response(post("/api/v2/reports/inputs").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.createObjectNode().put("title", "SRF contract").set("resultIds", JSON.valueToTree(List.of(resultId))).toString()), 201);
        String reportId = frozen.path("reportId").asText();
        JsonNode snapshot = frozen.path("sources").get(0).path("snapshot");
        assertEquals(2, snapshot.path("snapshotVersion").asInt());
        assertEquals(sample.path("resultView").path("body"), snapshot.path("result").path("body"));
        JsonNode frozenSubjects = snapshot.path("ISSUE".equals(kind) ? "issues" : "items");
        assertEquals(4, frozenSubjects.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(sample.path("feedbacks").get(i).path("subjectId"), frozenSubjects.get(i).path("id"));
            assertEquals(i + 1, frozenSubjects.get(i).path("ordinal").asInt());
            assertEquals(sample.path("feedbacks").get(i).path("assessment"), frozenSubjects.get(i).path("feedback").path("assessment"));
            assertEquals(sample.path("feedbacks").get(i).path("comment"), frozenSubjects.get(i).path("feedback").path("comment"));
        }
        var facts = new ReportFactsBuilder(JSON).build(sources);
        assertEquals(1, facts.summaries().get(0).accurate());
        assertEquals(1, facts.summaries().get(0).inaccurate());
        assertEquals(1, facts.summaries().get(0).unknown());
        assertEquals(1, facts.summaries().get(0).unassessed());

        String firstUrl = "/api/v2/feedback/" + kind + "/" + sample.path("feedbacks").get(0).path("subjectId").asText();
        response(put(firstUrl).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assessment\":\"INACCURATE\",\"comment\":\"later\",\"expectedRevision\":1}"), 200);
        JsonNode conflict = response(put(firstUrl).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assessment\":\"UNKNOWN\",\"comment\":\"stale\",\"expectedRevision\":1}"), 409);
        assertEquals("REVISION_CONFLICT", conflict.path("code").asText());
        assertEquals(2, conflict.path("details").path("current").path("revision").asInt());
        assertEquals("later", response(get(firstUrl), 200).path("comment").asText());
        assertEquals(snapshot, response(get("/api/v2/reports/" + reportId + "/inputs"), 200).path("sources").get(0).path("snapshot"));
        assertEquals(1, new ReportFactsBuilder(JSON).build(sources).summaries().get(0).accurate());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void httpValidationRejectsInvalidWritesWithoutCreatingFeedback(JsonNode sample) throws Exception {
        configure(sample);
        String kind = sample.path("kind").asText();
        String id = sample.path("feedbacks").get(0).path("subjectId").asText();
        String url = "/api/v2/feedback/" + kind + "/" + id;
        for (String request : List.of("{", "{\"assessment\":\"YES\",\"expectedRevision\":0}",
                "{\"expectedRevision\":-1}", "{\"comment\":\"missing revision\"}")) {
            assertEquals("VALIDATION_FAILED", response(put(url).contentType(MediaType.APPLICATION_JSON).content(request), 400).path("code").asText());
        }
        assertEquals("NOT_FOUND", response(get("/api/v2/feedback/" + kind + "/" + AgentIds.next()), 404).path("code").asText());
        assertEquals(0, response(get(url), 200).path("revision").asInt());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void incompleteResultsRemainReadableButHaveNoWritableItemsOrReportEligibility(JsonNode sample) throws Exception {
        AgentReviewResult row = configure(sample); row.setCompleteness("INCOMPLETE");
        String taskId = sample.path("taskId").asText();
        JsonNode result = response(get("/api/v2/review-tasks/" + taskId + "/targets/" + sample.path("targetId").asText() + "/result"), 200);
        assertEquals("INCOMPLETE", result.path("completeness").asText());
        assertEquals(0, result.path("items").size());
        assertEquals("CONFLICT", response(put("/api/v2/feedback/" + sample.path("kind").asText() + "/" + sample.path("feedbacks").get(0).path("subjectId").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"assessment\":\"ACCURATE\",\"expectedRevision\":0}"), 409).path("code").asText());
        response(post("/api/v2/reports/inputs").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.createObjectNode().put("title", "incomplete").set("resultIds", JSON.valueToTree(List.of(row.getId()))).toString()), 409);
        assertTrue(reports.isEmpty());
    }

    private AgentReviewResult configure(JsonNode sample) throws Exception {
        for (var type : List.of(AgentReviewTask.class, AgentTaskTarget.class, AgentReviewResult.class,
                AgentIssue.class, AgentReviewResultItem.class, AgentFeedback.class, AgentReport.class, AgentReportSource.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        AgentDatabase db = mock(AgentDatabase.class);
        when(db.inTransaction(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        AgentReviewTaskMapper tasks = mock(AgentReviewTaskMapper.class);
        AgentTaskTargetMapper targets = mock(AgentTaskTargetMapper.class);
        AgentReviewResultMapper results = mock(AgentReviewResultMapper.class);
        AgentIssueMapper issues = mock(AgentIssueMapper.class);
        AgentReviewResultItemMapper items = mock(AgentReviewResultItemMapper.class);
        AgentFeedbackMapper feedbackMapper = mock(AgentFeedbackMapper.class);
        AgentProjectMapper projects = mock(AgentProjectMapper.class);
        AgentMaterialMapper materials = mock(AgentMaterialMapper.class);
        AgentReportMapper reportMapper = mock(AgentReportMapper.class);
        AgentReportSourceMapper sourceMapper = mock(AgentReportSourceMapper.class);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(tasks);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targets);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(results);
        when(db.mapper(AgentIssueMapper.class)).thenReturn(issues);
        when(db.mapper(AgentReviewResultItemMapper.class)).thenReturn(items);
        when(db.mapper(AgentFeedbackMapper.class)).thenReturn(feedbackMapper);
        when(db.mapper(AgentProjectMapper.class)).thenReturn(projects);
        when(db.mapper(AgentMaterialMapper.class)).thenReturn(materials);
        when(db.mapper(AgentReportMapper.class)).thenReturn(reportMapper);
        when(db.mapper(AgentReportSourceMapper.class)).thenReturn(sourceMapper);
        var task = new AgentReviewTask();
        task.setId(sample.path("taskId").asText()); task.setProjectId(sample.path("projectId").asText());
        task.setRoute("SPECIALIST"); task.setSpecialistType(sample.path("kind").asText()); task.setState("SUCCEEDED");
        task.setInputSnapshot("{\"source\":{\"kind\":\"FILES\",\"branch\":\"dev\"}}");
        var target = new AgentTaskTarget(); target.setId(sample.path("targetId").asText()); target.setTaskId(task.getId());
        target.setPath(sample.path("material").path("path").asText()); target.setHeadSha("a".repeat(40)); target.setBaseSha("b".repeat(40));
        JsonNode view = sample.path("resultView");
        var result = new AgentReviewResult(); result.setId(view.path("id").asText()); result.setTaskId(task.getId());
        result.setTargetId(target.getId()); result.setKind(task.getSpecialistType()); result.setCompleteness("COMPLETE");
        result.setSchemaVersion("1"); result.setCreatedAt(LocalDateTime.parse(view.path("createdAt").asText()));
        result.setBody(view.path("body").toString()); result.setProvenance(view.path("provenance").toString());
        List<AgentIssue> issueRows = new ArrayList<>();
        for (JsonNode node : view.path("issues")) {
            ObjectNode plain = node.deepCopy(); plain.remove(List.of("locations", "sourceUnits"));
            var row = JSON.treeToValue(plain, AgentIssue.class); row.setResultId(result.getId());
            row.setLocations(node.path("locations").toString()); row.setSourceUnits(node.path("sourceUnits").toString()); issueRows.add(row);
        }
        List<AgentReviewResultItem> itemRows = new ArrayList<>();
        for (JsonNode node : view.path("items")) { var row = JSON.treeToValue(node, AgentReviewResultItem.class); row.setResultId(result.getId()); itemRows.add(row); }
        when(tasks.selectById(task.getId())).thenReturn(task); when(tasks.selectList(any())).thenReturn(List.of(task));
        when(targets.selectById(target.getId())).thenReturn(target); when(targets.selectList(any())).thenReturn(List.of(target));
        when(results.selectById(result.getId())).thenReturn(result); when(results.selectList(any())).thenReturn(List.of(result));
        when(issues.selectList(any())).thenReturn(issueRows); when(items.selectList(any())).thenReturn(itemRows);
        when(issues.selectById(anyString())).thenAnswer(call -> issueRows.stream().filter(row -> row.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(items.selectById(anyString())).thenAnswer(call -> itemRows.stream().filter(row -> row.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(materials.selectById(anyString())).thenReturn(JSON.treeToValue(sample.path("material"), AgentMaterial.class));
        when(feedbackMapper.selectList(any())).thenAnswer(call -> {
            Collection<Object> values = values(call.getArgument(0));
            return feedback.values().stream().filter(row -> values.contains(row.getSubjectKind()) && values.contains(row.getSubjectId())).toList();
        });
        when(feedbackMapper.selectOne(any())).thenAnswer(call -> {
            Collection<Object> values = values(call.getArgument(0));
            return feedback.values().stream().filter(row -> values.contains(row.getSubjectKind()) && values.contains(row.getSubjectId())).findFirst().orElse(null);
        });
        when(feedbackMapper.insert(any(AgentFeedback.class))).thenAnswer(call -> { AgentFeedback row = call.getArgument(0); feedback.put(row.getSubjectKind() + row.getSubjectId(), row); return 1; });
        when(feedbackMapper.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<AgentFeedback> query = call.getArgument(1);
            String id = (String) parameter(query, query.getSqlSegment(), "subject_id");
            String kind = (String) parameter(query, query.getSqlSegment(), "subject_kind");
            var row = feedback.get(kind + id);
            if (row == null || !row.getRevision().equals(parameter(query, query.getSqlSegment(), "revision"))) return 0;
            row.setAssessment((String) parameter(query, query.getSqlSet(), "assessment"));
            row.setComment((String) parameter(query, query.getSqlSet(), "comment"));
            row.setRevision((Integer) parameter(query, query.getSqlSet(), "revision"));
            return 1;
        });
        if (!"ISSUE".equals(result.getKind())) {
            var legacy = new AgentFeedback(); legacy.setSubjectKind("RESULT"); legacy.setSubjectId(result.getId());
            legacy.setAssessment("ACCURATE"); legacy.setComment("historical whole result"); legacy.setRevision(1);
            feedback.put("RESULT" + result.getId(), legacy);
        }
        when(reportMapper.insert(any(AgentReport.class))).thenAnswer(call -> { AgentReport row = call.getArgument(0); reports.put(row.getId(), row); return 1; });
        when(reportMapper.selectById(anyString())).thenAnswer(call -> reports.get(call.getArgument(0)));
        when(sourceMapper.insert(any(AgentReportSource.class))).thenAnswer(call -> { sources.add(call.getArgument(0)); return 1; });
        when(sourceMapper.selectList(any())).thenAnswer(call -> List.copyOf(sources));
        var taskService = new TaskService(db, JSON, mock(RepositoryReadService.class), mock(TaskDispatchService.class), new ModelInvocationProperties(), new TaskProperties());
        mvc = MockMvcBuilders.standaloneSetup(new ReviewTaskV2Controller(taskService, new TaskStatisticsService(db)),
                        new FeedbackV2Controller(new FeedbackService(db)), new ReportV2Controller(new ReportInputService(db, JSON), mock(ReportGenerationService.class)))
                .setControllerAdvice(new V2ExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON)).build();
        return result;
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int statusCode) throws Exception {
        return JSON.readTree(mvc.perform(request).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsByteArray());
    }

    private static Collection<Object> values(Wrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values();
    }

    private static Object parameter(AbstractWrapper<?, ?, ?> wrapper, String sql, String column) {
        var match = Pattern.compile("\\b" + column + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        if (!match.find()) throw new AssertionError("缺少 SQL 条件：" + column);
        return wrapper.getParamNameValuePairs().get(match.group(1));
    }
}
