package com.codereview.agent.execution;

import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskDispatchServiceTest {

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentReviewTaskMapper taskMapper;
    @Mock
    private AgentTaskTargetMapper targetMapper;
    @Mock
    private ObjectProvider<TaskWorker> workers;
    @Mock
    private TaskWorker worker;

    private TaskDispatchService dispatcher;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : java.util.List.of(AgentReviewTask.class, AgentTaskTarget.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        TaskProperties properties = new TaskProperties();
        dispatcher = spy(new TaskDispatchService(db, workers, properties));
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targetMapper);
        when(db.inTransaction(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        when(taskMapper.selectById(any())).thenReturn(task());
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(workers.orderedStream()).thenReturn(java.util.stream.Stream.of(worker));
        when(worker.route()).thenReturn("SPECIALIST");
        when(worker.execute(any())).thenReturn(TaskWorker.TaskOutcome.succeeded());
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    @Test
    void boundedQueueRejectsOverflowInsteadOfLeavingQueuedTasks() throws Exception {
        dispatcher.shutdown();
        TaskProperties properties = new TaskProperties(); properties.setDispatchConcurrency(1); properties.setDispatchQueueCapacity(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        dispatcher = new TaskDispatchService(db, workers, properties) {
            @Override void run(String id) {
                entered.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        try {
            dispatcher.dispatch("running");
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            dispatcher.dispatch("waiting"); dispatcher.dispatch("overflow");
            ArgumentCaptor<LambdaUpdateWrapper<AgentReviewTask>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(taskMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
            update.getValue().getSqlSegment();
            assertTrue(update.getValue().getParamNameValuePairs().containsValue("overflow"));
            assertTrue(update.getValue().getParamNameValuePairs().containsValue("DISPATCH_REJECTED"));
        } finally { release.countDown(); }
    }

    @Test
    void missingWorkerFailsTheTaskExplicitly() {
        when(workers.orderedStream()).thenReturn(java.util.stream.Stream.empty());
        doReturn(true).when(dispatcher).finish(any(), any(), any());

        dispatcher.run("task-1");

        verify(dispatcher).finish("task-1", TaskStates.FAILED, TaskStates.STOP_EXECUTOR_UNAVAILABLE);
    }

    @Test
    void workerOutcomeIsAppliedAsTheTerminalState() {
        doReturn(true).when(dispatcher).finish(any(), any(), any());

        dispatcher.run("task-1");

        verify(worker).execute(any());
        verify(dispatcher).finish("task-1", TaskStates.SUCCEEDED, null);
    }

    @Test
    void workerFailureIsRecordedAsFailed() {
        when(worker.execute(any())).thenThrow(new IllegalStateException("boom"));
        doReturn(true).when(dispatcher).finish(any(), any(), any());

        dispatcher.run("task-1");

        verify(dispatcher).finish("task-1", TaskStates.FAILED, TaskStates.STOP_EXECUTION_ERROR);
    }

    @Test
    void taskThatIsNoLongerQueuedIsNotStarted() {
        AgentReviewTask cancelled = task();
        cancelled.setState(TaskStates.CANCELLED);
        when(taskMapper.selectById(any())).thenReturn(cancelled);

        dispatcher.run("task-1");

        verify(workers, never()).orderedStream();
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void lateSuccessCannotOverwriteAnAlreadyTerminalTask() {
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertFalse(dispatcher.finish("task-1", TaskStates.SUCCEEDED, null));
        verify(taskMapper).update(any(), any());
    }

    @Test
    void deadlineClosesAsPartialWhenAnyTargetSucceeded() {
        when(targetMapper.selectCount(any())).thenReturn(2L);

        dispatcher.finishForDeadline("task-1");

        verify(taskMapper).update(any(), any());
        verify(targetMapper).selectCount(any());
        assertOpenTargetsClosedAs(TaskStates.CANCELLED);
    }

    @Test
    void missingWorkerClosesOpenTargetsAndPreservesTerminalOnes() {
        when(workers.orderedStream()).thenReturn(java.util.stream.Stream.empty());
        dispatcher.run("task-1");
        assertOpenTargetsClosedAs(TaskStates.FAILED);
        verify(db).inTransaction(any());
    }

    @Test
    void deadlineWithoutSuccessStillClosesOpenTargets() {
        when(targetMapper.selectCount(any())).thenReturn(0L);
        dispatcher.finishForDeadline("task-1");
        assertOpenTargetsClosedAs(TaskStates.CANCELLED);
    }

    @Test
    void losingTerminalUpdateCannotRewriteTargets() {
        when(taskMapper.update(any(), any())).thenReturn(0);
        assertFalse(dispatcher.terminate("task-1", TaskStates.FAILED, "TASK_DEADLINE"));
        verify(targetMapper, never()).update(any(), any());
    }

    @Test
    void targetWriteFailurePropagatesOutOfTheTransaction() {
        doThrow(new IllegalStateException("target write failed")).when(targetMapper).update(any(), any());
        assertThrows(IllegalStateException.class,
                () -> dispatcher.finish("task-1", TaskStates.FAILED, "EXECUTION_ERROR"));
        verify(db).inTransaction(any());
    }

    @SuppressWarnings("unchecked")
    private void assertOpenTargetsClosedAs(String state) {
        ArgumentCaptor<LambdaUpdateWrapper<AgentTaskTarget>> saved = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(targetMapper).update(any(), saved.capture());
        LambdaUpdateWrapper<AgentTaskTarget> update = saved.getValue();
        assertTrue(update.getSqlSegment().contains("NOT IN"), "已完成目标必须排除在更新之外");
        assertTrue(update.getParamNameValuePairs().values().containsAll(TaskStates.TARGET_TERMINAL));
        assertTrue(update.getParamNameValuePairs().values().contains("task-1"));
        assertTrue(update.getParamNameValuePairs().values().contains(state));
    }

    private static AgentReviewTask task() {
        AgentReviewTask task = new AgentReviewTask();
        task.setId("task-1");
        task.setState(TaskStates.QUEUED);
        task.setRevision(0);
        return task;
    }
}
