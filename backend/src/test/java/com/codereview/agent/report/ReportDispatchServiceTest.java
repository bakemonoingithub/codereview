package com.codereview.agent.report;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.mapper.AgentReportMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportDispatchServiceTest {
    @Test
    void fullQueueFailsExcessReportAndDoesNotRunIt() throws Exception {
        AgentDatabase db = mock(AgentDatabase.class);
        AgentReportMapper mapper = mock(AgentReportMapper.class);
        ReportExecutionWorker worker = mock(ReportExecutionWorker.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentReport.class);
        when(db.mapper(AgentReportMapper.class)).thenReturn(mapper);
        ReportGenerationProperties properties = new ReportGenerationProperties();
        properties.setDispatchConcurrency(1);
        properties.setDispatchQueueCapacity(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); release.await(5, TimeUnit.SECONDS); return null; })
                .when(worker).execute("running");
        ReportDispatchService dispatch = new ReportDispatchService(db, worker, properties);
        try {
            dispatch.dispatch("running");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            dispatch.dispatch("queued");
            dispatch.dispatch("excess");
            ArgumentCaptor<LambdaUpdateWrapper<AgentReport>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(mapper).update(any(), update.capture());
            update.getValue().getSqlSegment();
            assertTrue(update.getValue().getParamNameValuePairs().containsValue("excess"));
            assertTrue(update.getValue().getParamNameValuePairs().containsValue("FAILED"));
            verify(worker, never()).execute("excess");
        } finally {
            release.countDown();
            dispatch.shutdown();
        }
    }

    @Test
    void unexpectedWorkerFailureTerminatesOnlyActiveReport() throws Exception {
        AgentDatabase db = mock(AgentDatabase.class);
        AgentReportMapper mapper = mock(AgentReportMapper.class);
        ReportExecutionWorker worker = mock(ReportExecutionWorker.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentReport.class);
        when(db.mapper(AgentReportMapper.class)).thenReturn(mapper);
        CountDownLatch finished = new CountDownLatch(1);
        doThrow(new IllegalStateException("read failed")).when(worker).execute("report");
        when(mapper.update(any(), any())).thenAnswer(call -> { finished.countDown(); return 1; });
        ReportDispatchService dispatch = new ReportDispatchService(db, worker, new ReportGenerationProperties());
        try {
            dispatch.dispatch("report");
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            ArgumentCaptor<LambdaUpdateWrapper<AgentReport>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(mapper).update(any(), update.capture());
            String where = update.getValue().getSqlSegment();
            assertTrue(where.contains("state IN"), where);
            assertTrue(update.getValue().getParamNameValuePairs().containsValue("QUEUED"));
            assertTrue(update.getValue().getParamNameValuePairs().containsValue("RUNNING"));
            assertTrue(update.getValue().getParamNameValuePairs().containsValue("FAILED"));
        } finally {
            dispatch.shutdown();
        }
    }
}
