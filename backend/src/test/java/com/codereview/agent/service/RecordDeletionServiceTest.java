package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.*;
import com.codereview.agent.mapper.*;
import com.codereview.agent.support.AgentIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecordDeletionServiceTest {
    private final AgentDatabase db = mock(AgentDatabase.class);
    private final AgentReviewTaskMapper tasks = mock(AgentReviewTaskMapper.class);
    private final AgentReportMapper reports = mock(AgentReportMapper.class);
    private final String id = AgentIds.next();
    private final RecordDeletionService service = new RecordDeletionService(db);

    @BeforeEach void setup() {
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(tasks);
        when(db.mapper(AgentReportMapper.class)).thenReturn(reports);
        when(db.inTransaction(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    }

    @ParameterizedTest @ValueSource(strings={"SUCCEEDED","PARTIAL","FAILED","CANCELLED"})
    void terminalTasksCanBeDeletedWithoutChangingReports(String state) {
        var task = task(state);
        when(tasks.selectIncludingDeletedForUpdate(id)).thenReturn(task);
        when(tasks.markDeleted(id, 3)).thenReturn(1);
        service.task(id, 3);
        verify(tasks).markDeleted(id, 3);
        verifyNoInteractions(reports);
    }

    @ParameterizedTest @ValueSource(strings={"QUEUED","PREPARING","RUNNING"})
    void activeTasksCannotBeDeleted(String state) {
        when(tasks.selectIncludingDeletedForUpdate(id)).thenReturn(task(state));
        assertEquals(ErrorCode.CONFLICT, assertThrows(ApiException.class, () -> service.task(id,3)).errorCode());
        verify(tasks, never()).markDeleted(anyString(),anyInt());
    }

    @ParameterizedTest @ValueSource(strings={"INPUT_FROZEN","SUCCEEDED","FAILED"})
    void inactiveReportsCanBeDeleted(String state) {
        var report = new AgentReport(); report.setId(id); report.setState(state); report.setRevision(3);
        when(reports.selectIncludingDeletedForUpdate(id)).thenReturn(report);
        when(reports.markDeleted(id,3)).thenReturn(1);
        service.report(id,3);
        verify(reports).markDeleted(id,3);
        verifyNoInteractions(tasks);
    }

    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING"})
    void activeReportsCannotBeDeleted(String state) {
        var report = new AgentReport(); report.setState(state); report.setRevision(3);
        when(reports.selectIncludingDeletedForUpdate(id)).thenReturn(report);
        assertEquals(ErrorCode.CONFLICT, assertThrows(ApiException.class, () -> service.report(id,3)).errorCode());
        verify(reports,never()).markDeleted(anyString(),anyInt());
    }

    @Test void revisionConflictDoesNotWrite() {
        when(tasks.selectIncludingDeletedForUpdate(id)).thenReturn(task("SUCCEEDED"));
        assertEquals(ErrorCode.REVISION_CONFLICT, assertThrows(ApiException.class, () -> service.task(id,2)).errorCode());
        verify(tasks,never()).markDeleted(anyString(),anyInt());
    }

    @Test void concurrentChangeRollsBack() {
        when(tasks.selectIncludingDeletedForUpdate(id)).thenReturn(task("SUCCEEDED"));
        when(tasks.markDeleted(id,3)).thenReturn(0);
        assertEquals(ErrorCode.REVISION_CONFLICT, assertThrows(ApiException.class, () -> service.task(id,3)).errorCode());
    }

    @Test void repeatedDeletionIsIdempotent() {
        var task = task("SUCCEEDED"); task.setIsDeleted(1);
        when(tasks.selectIncludingDeletedForUpdate(id)).thenReturn(task);
        service.task(id,0);
        verify(tasks,never()).markDeleted(anyString(),anyInt());
    }

    @Test void missingRecordIsNotFound() {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> service.task(id,0)).errorCode());
    }

    @Test void genericCallsCannotBypassDeletedOwners() {
        var task = task("SUCCEEDED"); task.setIsDeleted(1);
        when(tasks.selectById(id)).thenReturn(task);
        var call = new AgentModelCall(); call.setTaskId(id);
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(ApiException.class,()->RecordVisibility.call(db,call)).errorCode());
        call.setTaskId(null);call.setReportId(id);
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(ApiException.class,()->RecordVisibility.call(db,call)).errorCode());
    }

    private AgentReviewTask task(String state) {
        var task = new AgentReviewTask();task.setId(id);task.setRevision(3);task.setState(state);return task;
    }
}
