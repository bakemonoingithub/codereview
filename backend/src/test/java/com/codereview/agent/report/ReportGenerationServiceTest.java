package com.codereview.agent.report;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReportSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 报告生成入口：只从 INPUT_FROZEN 发起，失败不产生可下载正文。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportGenerationServiceTest {

    private static final String REPORT_ID = "01J00000000000000000000000";
    private static final String MODEL_ID = "01J00000000000000000000001";

    @Mock private AgentDatabase db;
    @Mock private AgentReportMapper reportMapper;
    @Mock private AgentReportSourceMapper sourceMapper;
    @Mock private AgentModelCallMapper callMapper;
    @Mock private AgentModelConfigMapper modelMapper;
    @Mock private ReportDispatchService dispatcher;

    private ReportGenerationService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReport.class, AgentReportSource.class, AgentModelCall.class,
                AgentModelConfig.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        when(db.mapper(AgentReportMapper.class)).thenReturn(reportMapper);
        when(db.mapper(AgentReportSourceMapper.class)).thenReturn(sourceMapper);
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(callMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        when(sourceMapper.selectList(any())).thenReturn(List.of());
        when(callMapper.selectList(any())).thenReturn(List.of());
        service = new ReportGenerationService(db, json, dispatcher);
    }

    @Test
    void startingFromFrozenInputMovesToQueuedAndDispatches() {
        AgentReport frozen = report(ReportStates.INPUT_FROZEN);
        AgentReport queued = report(ReportStates.QUEUED);
        queued.setModelSnapshot("{\"id\":\"" + MODEL_ID + "\",\"name\":\"默认\",\"modelName\":\"qwen\"}");
        when(reportMapper.selectById(REPORT_ID)).thenReturn(frozen, queued);
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model());
        when(reportMapper.update(any(), any())).thenReturn(1);

        ReportDtos.ReportDetail detail = service.start(REPORT_ID,
                new ReportDtos.GenerateRequest(MODEL_ID, " 优先说明风险 "));

        assertEquals("QUEUED", detail.state());
        verify(dispatcher).dispatch(REPORT_ID);
    }

    @Test
    void reportAlreadyGeneratingCannotStartAgain() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report(ReportStates.RUNNING));

        ApiException error = assertThrows(ApiException.class, () -> service.start(REPORT_ID,
                new ReportDtos.GenerateRequest(MODEL_ID, null)));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void incompleteModelIsRejectedBeforeQueueing() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report(ReportStates.INPUT_FROZEN));
        AgentModelConfig model = model();
        model.setContextWindow(null);
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model);

        ApiException error = assertThrows(ApiException.class, () -> service.start(REPORT_ID,
                new ReportDtos.GenerateRequest(MODEL_ID, null)));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.errorCode());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void downloadOnlyWorksForSuccessfullySavedBody() {
        AgentReport success = report(ReportStates.SUCCEEDED);
        success.setTitle("订单/报告");
        success.setBody("# 已生成\n\n[S1]");
        when(reportMapper.selectById(REPORT_ID)).thenReturn(success);

        ReportGenerationService.Download download = service.download(REPORT_ID);

        assertEquals("订单_报告.md", download.fileName());
        assertEquals("# 已生成\n\n[S1]", new String(download.content(), java.nio.charset.StandardCharsets.UTF_8));

        AgentReport failed = report(ReportStates.FAILED);
        failed.setErrorCode("CONTEXT_LIMIT");
        when(reportMapper.selectById(REPORT_ID)).thenReturn(failed);

        assertEquals(ErrorCode.CONFLICT, assertThrows(ApiException.class,
                () -> service.download(REPORT_ID)).errorCode());
    }

    private AgentReport report(String state) {
        AgentReport report = new AgentReport();
        report.setId(REPORT_ID);
        report.setTitle("订单报告");
        report.setState(state);
        report.setRevision(1);
        report.setFrozenInput("{\"sourceCount\":1,\"tasks\":{},\"versions\":[],\"kinds\":{},\"sources\":[]}");
        report.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 21, 12, 0));
        return report;
    }

    private AgentModelConfig model() {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(MODEL_ID);
        model.setName("默认");
        model.setBaseUrl("https://model.local/v1");
        model.setModelName("qwen");
        model.setContextWindow(32000);
        model.setMaxOutput(4096);
        model.setProtocol("OPENAI_CHAT");
        model.setRevision(1);
        return model;
    }
}
