package com.codereview.agent.execution;

import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentRun;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.entity.AgentToolCall;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskDeadlineSweeperTest {

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentReviewTaskMapper taskMapper;
    @Mock
    private AgentTaskTargetMapper targetMapper;
    @Mock
    private TaskDispatchService dispatcher;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentToolCallMapper toolCallMapper;

    private TaskDeadlineSweeper sweeper;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReviewTask.class, AgentTaskTarget.class,
                AgentRun.class, AgentToolCall.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targetMapper);
        when(db.mapper(AgentRunMapper.class)).thenReturn(runMapper);
        when(db.mapper(AgentToolCallMapper.class)).thenReturn(toolCallMapper);
        AgentReviewTask expired = new AgentReviewTask();
        expired.setId("task-1");
        expired.setState(TaskStates.RUNNING);
        expired.setRevision(2);
        expired.setDeadlineAt(LocalDateTime.now().minusMinutes(1));
        when(taskMapper.selectList(any())).thenReturn(List.of(expired));
        when(targetMapper.selectCount(any())).thenReturn(0L);
        when(targetMapper.update(any(), any())).thenReturn(1);
        when(dispatcher.terminate(any(), any(), any())).thenReturn(true);
        sweeper = new TaskDeadlineSweeper(db, dispatcher);
    }

    @Test
    void expiredTaskDelegatesAtomicTaskAndTargetClosureToDispatcher() {
        int closed = sweeper.sweep();

        assertEquals(1, closed);
        verify(dispatcher).terminate("task-1", TaskStates.FAILED, TaskStates.STOP_TASK_DEADLINE);
        verify(targetMapper, never()).update(any(), any());
    }

    @Test
    void expiredAgentTaskWithToolWorkClosesAsPartial() {
        AgentReviewTask expired = new AgentReviewTask();
        expired.setId("agent-1");
        expired.setRoute("AGENT");
        expired.setState(TaskStates.RUNNING);
        expired.setDeadlineAt(LocalDateTime.now().minusMinutes(1));
        when(taskMapper.selectList(any())).thenReturn(List.of(expired));
        AgentRun run = new AgentRun();
        run.setTaskId("agent-1");
        run.setToolAttempts(4);
        when(runMapper.selectById("agent-1")).thenReturn(run);

        int closed = sweeper.sweep();

        assertEquals(1, closed);
        verify(dispatcher).terminate("agent-1", TaskStates.PARTIAL, TaskStates.STOP_TASK_DEADLINE);
    }
}
