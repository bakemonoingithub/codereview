package com.codereview.agent.support;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
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

/** 提示词版本共同检查；场景默认与错误分类留在调用入口。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromptVersionResolverTest {

    private static final String PROMPT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E4";
    private static final String CURRENT_VERSION_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E5";
    private static final String EXPLICIT_VERSION_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E9";

    @Mock private AgentDatabase db;
    @Mock private AgentPromptVersionMapper versionMapper;

    @BeforeEach
    void setUp() {
        when(db.mapper(AgentPromptVersionMapper.class)).thenReturn(versionMapper);
    }

    @Test
    void omittedVersionUsesCurrentVersion() {
        when(versionMapper.selectById(CURRENT_VERSION_ID))
                .thenReturn(version(CURRENT_VERSION_ID, PROMPT_ID, "当前正文"));

        AgentPromptVersion version = PromptVersionResolver.resolve(db, prompt(), null);

        assertEquals(CURRENT_VERSION_ID, version.getId());
        assertEquals("当前正文", version.getContent());
    }

    @Test
    void explicitBlankVersionDoesNotFallBackToCurrentVersion() {
        ApiException error = assertThrows(ApiException.class,
                () -> PromptVersionResolver.resolve(db, prompt(), " "));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.errorCode());
        verify(versionMapper, never()).selectById(any());
    }

    @Test
    void missingOrForeignVersionIsRejectedAsValidationFailure() {
        when(versionMapper.selectById(CURRENT_VERSION_ID)).thenReturn(null);
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
                () -> PromptVersionResolver.resolve(db, prompt(), null)).errorCode());

        when(versionMapper.selectById(CURRENT_VERSION_ID))
                .thenReturn(version(CURRENT_VERSION_ID, "01J9Z6Q3W4X5Y6Z7A8B9C0D1E8", "其他提示词"));
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
                () -> PromptVersionResolver.resolve(db, prompt(), null)).errorCode());
    }

    @Test
    void blankContentForImplicitSelectionKeepsConfigIncomplete() {
        when(versionMapper.selectById(CURRENT_VERSION_ID))
                .thenReturn(version(CURRENT_VERSION_ID, PROMPT_ID, " \n "));

        ApiException error = assertThrows(ApiException.class,
                () -> PromptVersionResolver.resolve(db, prompt(), null));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.errorCode());
    }

    @Test
    void explicitSelectionUsesCallerBlankContentClassification() {
        when(versionMapper.selectById(EXPLICIT_VERSION_ID))
                .thenReturn(version(EXPLICIT_VERSION_ID, PROMPT_ID, " "));

        ApiException error = assertThrows(ApiException.class,
                () -> PromptVersionResolver.resolveExplicit(db, prompt(), EXPLICIT_VERSION_ID,
                        ErrorCode.VALIDATION_FAILED, "所选报告通用规则版本正文不能为空"));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        assertEquals("所选报告通用规则版本正文不能为空", error.getMessage());
    }

    @Test
    void explicitSelectionDoesNotConsultCurrentVersion() {
        when(versionMapper.selectById(EXPLICIT_VERSION_ID)).thenReturn(null);

        assertThrows(ApiException.class,
                () -> PromptVersionResolver.resolveExplicit(db, prompt(), EXPLICIT_VERSION_ID,
                        ErrorCode.VALIDATION_FAILED, "正文为空"));

        verify(versionMapper, never()).selectById(CURRENT_VERSION_ID);
    }

    private static AgentPrompt prompt() {
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(PROMPT_ID);
        prompt.setCurrentVersionId(CURRENT_VERSION_ID);
        return prompt;
    }

    private static AgentPromptVersion version(String id, String promptId, String content) {
        AgentPromptVersion version = new AgentPromptVersion();
        version.setId(id);
        version.setPromptId(promptId);
        version.setVersionNo(1);
        version.setContent(content);
        return version;
    }
}
