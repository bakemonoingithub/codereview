package com.codereview.agent.report;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.invocation.InvocationRequest;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReportSourceMapper;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptFact;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.prompt.PromptMessage;
import com.codereview.agent.prompt.PromptPurpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codereview.agent.support.AgentTime;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 报告执行：成功正文必须校验引用；失败保留来源且不写正文。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportExecutionWorkerTest {

    private static final String REPORT_ID = "01J00000000000000000000000";
    private static final String MODEL_ID = "01J00000000000000000000001";

    @Mock private AgentDatabase db;
    @Mock private AgentReportMapper reportMapper;
    @Mock private AgentReportSourceMapper sourceMapper;
    @Mock private AgentModelConfigMapper modelMapper;
    @Mock private ReportFactsBuilder factsBuilder;
    @Mock private PromptAssemblyService assembly;
    @Mock private ModelInvocationService invocation;

    private final ObjectMapper json = new ObjectMapper();
    private ReportExecutionWorker worker;

    @Test
    void lateResponseCannotPublishSuccessfulReport() {
        stubQueuedReport();
        when(sourceMapper.selectList(any())).thenReturn(List.of(new AgentReportSource()));
        when(factsBuilder.build(any())).thenReturn(facts());
        when(assembly.assemble(any())).thenReturn(prompt());
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model());
        try (var clock = org.mockito.Mockito.mockStatic(AgentTime.class)) {
            LocalDateTime start = LocalDateTime.of(2026, 10, 21, 1, 0);
            clock.when(AgentTime::now).thenReturn(start);
            when(invocation.invoke(any())).thenAnswer(call -> {
                clock.when(AgentTime::now).thenReturn(start.plusMinutes(11));
                return new InvocationResult("call-1", "迟到正文 [S1]", null, "stop", 1, 1, 1, "m", "v");
            });
            worker.execute(REPORT_ID);
        }
        var finish = finishUpdate();
        assertTrue(finish.getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(finish.getParamNameValuePairs().containsValue("UPSTREAM_TIMEOUT"));
        assertTrue(!finish.getParamNameValuePairs().containsValue("迟到正文 [S1]"));
    }

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReport.class, AgentReportSource.class, AgentModelConfig.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        when(db.mapper(AgentReportMapper.class)).thenReturn(reportMapper);
        when(db.mapper(AgentReportSourceMapper.class)).thenReturn(sourceMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        worker = new ReportExecutionWorker(db, json, factsBuilder, assembly, invocation,
                new ReportGenerationProperties());
    }

    @Test
    void successStoresBodyOnlyAfterCitationValidation() {
        stubQueuedReport();
        when(sourceMapper.selectList(any())).thenReturn(List.of(new AgentReportSource()));
        when(factsBuilder.build(any())).thenReturn(facts());
        when(assembly.assemble(any())).thenReturn(prompt());
        when(invocation.invoke(any())).thenReturn(new InvocationResult("call-1", "# 报告\n\n结论 [S1]。",
                null, "stop", 1, 1, 1, "m", "v"));
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model());
        when(reportMapper.update(any(), any())).thenReturn(1);

        worker.execute(REPORT_ID);

        ArgumentCaptor<LambdaUpdateWrapper<AgentReport>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reportMapper, org.mockito.Mockito.times(2)).update(any(), updates.capture());
        LambdaUpdateWrapper<AgentReport> finish = updates.getAllValues().get(1);
        assertTrue(finish.getParamNameValuePairs().containsValue("SUCCEEDED"), finish.toString());
        assertTrue(finish.getParamNameValuePairs().containsValue("# 报告\n\n结论 [S1]。"), finish.toString());
    }

    @Test
    void unknownCitationFailsWithoutWritingBody() {
        stubQueuedReport();
        when(sourceMapper.selectList(any())).thenReturn(List.of(new AgentReportSource()));
        when(factsBuilder.build(any())).thenReturn(facts());
        when(assembly.assemble(any())).thenReturn(prompt());
        when(invocation.invoke(any())).thenReturn(new InvocationResult("call-1", "结论 [S9]。",
                null, "stop", 1, 1, 1, "m", "v"));
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model());
        when(reportMapper.update(any(), any())).thenReturn(1);

        worker.execute(REPORT_ID);

        LambdaUpdateWrapper<AgentReport> finish = finishUpdate();
        assertTrue(finish.getParamNameValuePairs().containsValue("FAILED"), finish.toString());
        assertTrue(finish.getParamNameValuePairs().containsValue("REPORT_CITATION_INVALID"),
                finish.toString());
        assertTrue(finish.getSqlSet().contains("body"), finish.getSqlSet());
    }

    @Test
    void modelFailureKeepsFrozenInputsAndRecordsError() {
        stubQueuedReport();
        when(sourceMapper.selectList(any())).thenReturn(List.of(new AgentReportSource()));
        when(factsBuilder.build(any())).thenReturn(facts());
        when(assembly.assemble(any())).thenReturn(prompt());
        when(invocation.invoke(any())).thenThrow(new ApiException(ErrorCode.CONTEXT_LIMIT,
                "完整请求超过模型上下文窗口，未发送"));
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model());
        when(reportMapper.update(any(), any())).thenReturn(1);

        worker.execute(REPORT_ID);

        LambdaUpdateWrapper<AgentReport> finish = finishUpdate();
        assertTrue(finish.getParamNameValuePairs().containsValue("FAILED"), finish.toString());
        assertTrue(finish.getParamNameValuePairs().containsValue("CONTEXT_LIMIT"), finish.toString());
        assertTrue(!finish.getSqlSet().contains("frozen_input"), finish.getSqlSet());
    }

    @Test
    void reportThatIsNoLongerQueuedIsNotReexecuted() {
        AgentReport report = report();
        report.setState(ReportStates.SUCCEEDED);
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report);

        worker.execute(REPORT_ID);

        verify(invocation, never()).invoke(any());
        verify(reportMapper, never()).update(any(), any());
    }

    private void stubQueuedReport() {
        AgentReport report = report();
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report);
        when(reportMapper.update(any(), any())).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<AgentReport> finishUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<AgentReport>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reportMapper, org.mockito.Mockito.times(2)).update(any(), updates.capture());
        return updates.getAllValues().get(1);
    }

    private AgentReport report() {
        AgentReport report = new AgentReport();
        report.setId(REPORT_ID);
        report.setTitle("订单报告");
        report.setState(ReportStates.QUEUED);
        report.setRevision(1);
        report.setModelSnapshot("{\"id\":\"" + MODEL_ID + "\",\"name\":\"默认\","
                + "\"baseUrl\":\"https://model.local/v1\",\"modelName\":\"qwen\","
                + "\"contextWindow\":32000,\"maxOutput\":4096,\"tokenizer\":\"qwen\","
                + "\"protocol\":\"OPENAI_CHAT\",\"revision\":1}");
        report.setInstructionSnapshot("{\"version\":\"1\",\"text\":\"优先说明风险\"}");
        return report;
    }

    private AgentModelConfig model() {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(MODEL_ID);
        model.setCredential("secret");
        return model;
    }

    private ReportFactsBuilder.ReportFacts facts() {
        return new ReportFactsBuilder.ReportFacts(
                List.of(new PromptFact("S1", "来源事实")), List.of(), 1, 1, 1,
                Map.of("ISSUE", 1), List.<ReportDtos.SourceSummary>of());
    }

    private AssembledPrompt prompt() {
        return new AssembledPrompt(PromptPurpose.REPORT, "1",
                List.of(new PromptMessage("system", "s"), new PromptMessage("user", "u")),
                "s", "u", false, 10, 20, "m", "v");
    }
}
