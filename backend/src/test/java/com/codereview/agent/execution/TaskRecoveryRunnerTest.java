package com.codereview.agent.execution;

import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskRecoveryRunnerTest {

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentReviewTaskMapper taskMapper;
    @Mock
    private AgentTaskTargetMapper targetMapper;
    @Mock
    private AgentModelCallMapper callMapper;

    private TaskRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReviewTask.class, AgentTaskTarget.class,
                AgentModelCall.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targetMapper);
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(callMapper);
        when(taskMapper.update(any(), any())).thenReturn(3);
        when(targetMapper.update(any(), any())).thenReturn(2, 4);
        when(callMapper.update(any(), any())).thenReturn(5);
        runner = new TaskRecoveryRunner(db, new TaskProperties());
    }

    @Test
    void restartClosesTasksTargetsAndCallsWithoutResuming() {
        TaskRecoveryRunner.RecoveryResult result = runner.recover();

        assertEquals(3, result.tasks());
        assertEquals(6, result.targets());
        assertEquals(5, result.calls());
        verify(taskMapper).update(any(), any());
        verify(targetMapper, org.mockito.Mockito.times(2)).update(any(), any());
        verify(callMapper).update(any(), any());
    }
}
