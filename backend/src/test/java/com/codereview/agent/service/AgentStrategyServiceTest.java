package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.StrategyDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentReviewStrategy;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
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

    private AgentStrategyService service;

    @BeforeEach
    void setUp() {
        service = new AgentStrategyService(db);
        when(db.mapper(AgentReviewStrategyMapper.class)).thenReturn(strategyMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        when(db.mapper(AgentPromptMapper.class)).thenReturn(promptMapper);
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
}
