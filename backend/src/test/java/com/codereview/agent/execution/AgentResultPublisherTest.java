package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.tools.AgentSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentResultPublisherTest {

    private static final String TASK_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";

    @Mock private AgentDatabase db;
    @Mock private AgentReviewTaskMapper taskMapper;
    @Mock private AgentReviewResultMapper resultMapper;

    private AgentResultPublisher publisher;
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentReviewTask task;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentReviewTask.class);
        publisher = new AgentResultPublisher(db, mapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
        when(db.inTransaction(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setProjectId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3");
        task.setState(TaskStates.RUNNING);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(resultMapper.selectCount(any())).thenReturn(0L);
        when(resultMapper.insert(any(AgentReviewResult.class))).thenReturn(1);
        when(taskMapper.update(isNull(), any())).thenReturn(1);
    }

    @Test
    void publishesAFrozenAgentResultWithProvenance() throws Exception {
        String resultId = publisher.publish(TASK_ID, submission(), materials(), comparisons(), 3);

        ArgumentCaptor<AgentReviewResult> saved = ArgumentCaptor.forClass(AgentReviewResult.class);
        verify(resultMapper).insert(saved.capture());
        assertEquals(resultId, saved.getValue().getId());
        assertEquals("AGENT", saved.getValue().getKind());
        assertEquals("1", saved.getValue().getSchemaVersion());
        assertEquals("COMPLETE", saved.getValue().getCompleteness());
        JsonNode body = mapper.readTree(saved.getValue().getBody());
        assertEquals("ANSWERED", body.path("answerStatus").asText());
        assertEquals("src/A.java", body.path("citations").get(0).path("path").asText());
        assertEquals(10, body.path("citations").get(0).path("startLine").asInt());
        JsonNode provenance = mapper.readTree(saved.getValue().getProvenance());
        assertEquals(3, provenance.path("toolCalls").asInt());
        assertEquals("C1", provenance.path("comparisons").get(0).path("id").asText());
        verify(taskMapper).update(isNull(), any());
    }

    @Test
    void insufficientEvidenceIsStoredAsCompleteWithAStopReason() throws Exception {
        AgentSubmission insufficient = new AgentSubmission("标题", "材料不足",
                AgentSubmission.INSUFFICIENT_EVIDENCE, "无法确认支付实现。", List.of(),
                List.of("没有取得支付实现。"));
        publisher.publish(TASK_ID, insufficient, materials(), comparisons(), 1);

        ArgumentCaptor<AgentReviewResult> saved = ArgumentCaptor.forClass(AgentReviewResult.class);
        verify(resultMapper).insert(saved.capture());
        JsonNode body = mapper.readTree(saved.getValue().getBody());
        assertEquals(AgentSubmission.INSUFFICIENT_EVIDENCE, body.path("answerStatus").asText());
        assertEquals(1, body.path("unresolved").size());
    }

    @Test
    void alreadyPublishedResultsCannotBeReplaced() {
        when(resultMapper.selectCount(any())).thenReturn(1L);

        ApiException error = assertThrows(ApiException.class,
                () -> publisher.publish(TASK_ID, submission(), materials(), comparisons(), 1));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
    }

    @Test
    void cancelledTasksCannotPublish() {
        task.setCancelRequestedAt(com.codereview.agent.support.AgentTime.now());

        ApiException error = assertThrows(ApiException.class,
                () -> publisher.publish(TASK_ID, submission(), materials(), comparisons(), 1));

        assertEquals(ErrorCode.TASK_CANCELLED, error.errorCode());
        verify(resultMapper, org.mockito.Mockito.never()).insert(any(AgentReviewResult.class));
    }

    private static AgentSubmission submission() {
        return new AgentSubmission("重试链路", "调用方尚缺完整幂等保证。", AgentSubmission.ANSWERED,
                "结论。[E1]", List.of(new AgentSubmission.Citation("E1", "m1", 10, 12)), List.of());
    }

    private static List<AgentSubmission.SentMaterial> materials() {
        return List.of(new AgentSubmission.SentMaterial("m1", "src/A.java", "FILE",
                "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6", 1, 20, "hash"));
    }

    private static List<Map<String, Object>> comparisons() {
        return List.of(Map.of("id", "C1", "headSha", "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6"));
    }
}
