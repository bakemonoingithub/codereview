package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ModelDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentModelValidation;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentModelValidationMapper;
import com.codereview.agent.mapper.AgentReviewStrategyMapper;
import com.codereview.agent.model.ModelProbe;
import com.codereview.agent.model.ModelProbeOutcome;
import com.codereview.agent.support.AgentIds;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentModelServiceTest {

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentModelConfigMapper mapper;
    @Mock
    private AgentModelValidationMapper validationMapper;
    @Mock
    private AgentReviewStrategyMapper strategyMapper;
    @Mock
    private ModelProbe probe;

    private AgentModelService service;

    @BeforeEach
    void setUp() {
        service = new AgentModelService(db, probe, new ObjectMapper());
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(mapper);
        when(db.mapper(AgentModelValidationMapper.class)).thenReturn(validationMapper);
        when(db.mapper(AgentReviewStrategyMapper.class)).thenReturn(strategyMapper);
    }

    @Test
    void validationFromAnOlderRevisionIsReportedAsExpired() {
        AgentModelConfig model = model(2);
        when(mapper.selectById(any())).thenReturn(model);
        when(validationMapper.selectList(any())).thenReturn(List.of(validation(1, "PASSED")));

        assertEquals("EXPIRED", service.detail(model.getId()).validationStatus());
    }

    @Test
    void validationStatusIsNoneWithoutRecordAndPassedForCurrentRevision() {
        AgentModelConfig model = model(2);
        when(mapper.selectById(any())).thenReturn(model);
        when(validationMapper.selectList(any())).thenReturn(List.of());
        assertEquals("NONE", service.detail(model.getId()).validationStatus());

        when(validationMapper.selectList(any())).thenReturn(List.of(validation(2, "PASSED")));
        assertEquals("PASSED", service.detail(model.getId()).validationStatus());
    }

    @Test
    void validateStoresTheResultAgainstTheCurrentRevision() {
        AgentModelConfig model = model(4);
        when(mapper.selectById(any())).thenReturn(model);
        when(probe.probe(any())).thenReturn(new ModelProbeOutcome(true, "连通成功"));

        ModelDtos.ValidationResult result = service.validate(model.getId());

        assertEquals("PASSED", result.result());
        assertEquals(4, result.configRevision());
        ArgumentCaptor<AgentModelValidation> inserted = ArgumentCaptor.forClass(AgentModelValidation.class);
        verify(validationMapper).insert(inserted.capture());
        assertEquals(4, inserted.getValue().getConfigRevision());
        assertEquals("PASSED", inserted.getValue().getResult());
    }

    @Test
    void failedProbeIsRecordedRatherThanThrown() {
        AgentModelConfig model = model(0);
        when(mapper.selectById(any())).thenReturn(model);
        when(probe.probe(any())).thenReturn(new ModelProbeOutcome(false, "网关返回 HTTP 401"));

        ModelDtos.ValidationResult result = service.validate(model.getId());

        assertEquals("FAILED", result.result());
        verify(validationMapper).insert(any(AgentModelValidation.class));
    }

    @Test
    void deleteRefusesWhenStrategiesStillReferenceTheModel() {
        AgentModelConfig model = model(0);
        when(mapper.selectById(any())).thenReturn(model);
        when(strategyMapper.selectCount(any())).thenReturn(2L);

        ApiException error = assertThrows(ApiException.class, () -> service.delete(model.getId()));

        assertEquals(ErrorCode.CONFIG_IN_USE, error.errorCode());
        verify(mapper, never()).deleteById("never-called");
    }

    @Test
    void credentialIsNeverPartOfTheView() {
        AgentModelConfig model = model(0);
        model.setCredentialRef("secret");
        when(mapper.selectById(any())).thenReturn(model);
        when(validationMapper.selectList(any())).thenReturn(List.of());

        ModelDtos.View view = service.detail(model.getId());

        assertTrue(view.credentialConfigured());
        assertFalse(view.toString().contains("secret"));
    }

    private static AgentModelConfig model(int revision) {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(AgentIds.next());
        model.setName("默认模型");
        model.setBaseUrl("https://gateway.local/v1");
        model.setModelName("deepseek-chat");
        model.setProtocol("OPENAI_CHAT");
        model.setRevision(revision);
        return model;
    }

    private static AgentModelValidation validation(int revision, String result) {
        AgentModelValidation validation = new AgentModelValidation();
        validation.setId(AgentIds.next());
        validation.setConfigRevision(revision);
        validation.setResult(result);
        validation.setScope("CONNECTIVITY");
        return validation;
    }
}
