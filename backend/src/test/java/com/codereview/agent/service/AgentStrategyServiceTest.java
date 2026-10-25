package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.StrategyDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.entity.AgentReviewStrategy;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.mapper.AgentReviewStrategyMapper;
import com.codereview.agent.support.AgentIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentStrategyServiceTest {

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentReviewStrategyMapper strategyMapper;
    @Mock
    private AgentModelConfigMapper modelMapper;
    @Mock
    private AgentPromptMapper promptMapper;

    @Mock private AgentPromptVersionMapper versionMapper;

    private AgentStrategyService service;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentReviewStrategy.class);
        service = new AgentStrategyService(db);
        when(db.mapper(AgentReviewStrategyMapper.class)).thenReturn(strategyMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        when(db.mapper(AgentPromptMapper.class)).thenReturn(promptMapper);
        when(db.mapper(AgentPromptVersionMapper.class)).thenReturn(versionMapper);
    }

    @Test
    void strategyRequiresAModel() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(new StrategyDtos.Create("订单规则", "ISSUE", null, null)));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        verify(strategyMapper, never()).insert(any(AgentReviewStrategy.class));
    }

    @Test
    void strategyRejectsMissingModelReference() {
        when(modelMapper.selectById(any())).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(new StrategyDtos.Create("订单规则", "ISSUE", AgentIds.next(), null)));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
    }

    @Test
    void strategyRejectsPromptWithAnotherPurpose() {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(AgentIds.next());
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(AgentIds.next());
        prompt.setPurpose("REPORT");
        when(modelMapper.selectById(any())).thenReturn(model);
        when(promptMapper.selectById(any())).thenReturn(prompt);

        ApiException error = assertThrows(ApiException.class, () -> service.create(
                new StrategyDtos.Create("订单规则", "ISSUE", model.getId(), prompt.getId())));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
    }

    @Test
    void strategySavesStableReferencesWhenEverythingIsValid() {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(AgentIds.next());
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(AgentIds.next());
        prompt.setPurpose("SPECIALIST");
        when(modelMapper.selectById(any())).thenReturn(model);
        when(promptMapper.selectById(any())).thenReturn(prompt);

        StrategyDtos.View view = service.create(new StrategyDtos.Create(
                "订单规则", "ISSUE", model.getId(), prompt.getId()));

        assertEquals(model.getId(), view.modelConfigId());
        assertEquals(prompt.getId(), view.promptId());
        assertEquals(0, view.revision());
        verify(strategyMapper).insert(any(AgentReviewStrategy.class));
    }
    @Test
    void strategyPinsAnOwnedHistoricalVersionAndRejectsCrossPromptOrOrphanVersions() {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(AgentIds.next());
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(AgentIds.next());
        prompt.setPurpose("SPECIALIST");
        prompt.setCurrentVersionId(AgentIds.next());
        AgentPromptVersion old = new AgentPromptVersion();
        old.setId(AgentIds.next());
        old.setPromptId(prompt.getId());
        old.setContent("历史规则");
        when(modelMapper.selectById(any())).thenReturn(model);
        when(promptMapper.selectById(any())).thenReturn(prompt);
        when(versionMapper.selectById(old.getId())).thenReturn(old);

        StrategyDtos.View view = service.create(new StrategyDtos.Create(
                "历史规则策略", "ISSUE", model.getId(), prompt.getId(), old.getId()));
        assertEquals(old.getId(), view.promptVersionId());

        old.setPromptId(AgentIds.next());
        assertThrows(ApiException.class, () -> service.create(new StrategyDtos.Create(
                "串用版本", "ISSUE", model.getId(), prompt.getId(), old.getId())));
        assertThrows(ApiException.class, () -> service.create(new StrategyDtos.Create(
                "缺失版本", "ISSUE", model.getId(), prompt.getId(), AgentIds.next())));
        assertThrows(ApiException.class, () -> service.create(new StrategyDtos.Create(
                "孤立版本", "ISSUE", model.getId(), null, old.getId())));
    }

    @Test
    void editingCanClearBothPromptAndVersionReferences() {
        String strategyId = AgentIds.next();
        AgentReviewStrategy current = new AgentReviewStrategy();
        current.setId(strategyId);
        current.setRevision(2);
        current.setPromptId(AgentIds.next());
        current.setPromptVersionId(AgentIds.next());
        AgentModelConfig model = new AgentModelConfig();
        model.setId(AgentIds.next());
        when(strategyMapper.selectById(strategyId)).thenReturn(current);
        when(modelMapper.selectById(model.getId())).thenReturn(model);
        when(strategyMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);

        service.update(strategyId, new StrategyDtos.Update("清空规则", "ISSUE", model.getId(), null, null, 2));
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentReviewStrategy>> update =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(strategyMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertEquals(true, update.getValue().getSqlSet().contains("prompt_id="));
        assertEquals(true, update.getValue().getSqlSet().contains("prompt_version_id="));
        assertEquals(2, update.getValue().getParamNameValuePairs().values().stream().filter(java.util.Objects::isNull).count());
    }

}
