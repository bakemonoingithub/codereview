package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.PromptDtos;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.mapper.AgentReviewStrategyMapper;
import com.codereview.common.TextDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentPromptServiceTest {

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentPromptMapper promptMapper;
    @Mock
    private AgentPromptVersionMapper versionMapper;
    @Mock
    private AgentReviewStrategyMapper strategyMapper;

    private AgentPromptService service;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentPrompt.class);
        service = new AgentPromptService(db);
        when(db.mapper(AgentPromptMapper.class)).thenReturn(promptMapper);
        when(db.mapper(AgentPromptVersionMapper.class)).thenReturn(versionMapper);
        when(db.mapper(AgentReviewStrategyMapper.class)).thenReturn(strategyMapper);
        when(db.inTransaction(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    void createWritesFirstVersionAndPointsPromptAtIt() {
        PromptDtos.View view = service.create(new PromptDtos.Create(
                "通用规则", "描述", List.of("java", "通用"), "SPECIALIST", "规则正文"));

        assertEquals(1, view.currentVersion().versionNo());
        assertEquals("规则正文", view.currentVersion().content());
        ArgumentCaptor<AgentPrompt> insertedPrompt = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(promptMapper).insert(insertedPrompt.capture());
        assertTrue(insertedPrompt.getValue().getCurrentVersionId() != null);
        assertEquals(0, insertedPrompt.getValue().getRevision());
        ArgumentCaptor<AgentPromptVersion> insertedVersion = ArgumentCaptor.forClass(AgentPromptVersion.class);
        verify(versionMapper).insert(insertedVersion.capture());
        assertEquals(1, insertedVersion.getValue().getVersionNo());
    }

    @Test
    void addVersionIncrementsAndKeepsOldContent() {
        AgentPrompt prompt = prompt(1);
        when(promptMapper.selectById(any())).thenReturn(prompt);
        when(versionMapper.selectList(any())).thenReturn(List.of(version(prompt.getId(), 3, "旧正文")));
        when(promptMapper.update(any(), any())).thenReturn(1);

        PromptDtos.View view = service.addVersion(prompt.getId(),
                new PromptDtos.VersionCreate("新正文", 1));

        assertEquals(4, view.currentVersion().versionNo());
        verify(promptMapper).update(org.mockito.ArgumentMatchers.argThat(patch ->
                patch.getRevision() == 2 && patch.getCurrentVersionId() != null), any());
    }

    @Test
    void staleRevisionOnVersionWriteIsRejected() {
        AgentPrompt prompt = prompt(1);
        when(promptMapper.selectById(any())).thenReturn(prompt);

        ApiException error = assertThrows(ApiException.class, () -> service.addVersion(prompt.getId(),
                new PromptDtos.VersionCreate("新正文", 0)));

        assertEquals(ErrorCode.REVISION_CONFLICT, error.errorCode());
        verify(versionMapper, never()).insert(any(AgentPromptVersion.class));
    }

    @Test
    void clearingDescriptionAndTagsUpdatesThemToNull() {
        AgentPrompt prompt = prompt(0);
        when(promptMapper.selectById(any())).thenReturn(prompt);
        when(promptMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);

        service.update(prompt.getId(), new PromptDtos.Update("新名称", "", List.of(), "SPECIALIST", 0));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentPrompt>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(promptMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        String set = captor.getValue().getSqlSet();
        assertTrue(set.contains("description"), "清空描述必须出现在 SET 里：" + set);
        assertTrue(set.contains("tags"), "清空标签必须出现在 SET 里：" + set);
    }

    @Test
    void diffRejectsVersionFromAnotherPrompt() {
        AgentPrompt prompt = prompt(0);
        when(promptMapper.selectById(any())).thenReturn(prompt);
        when(versionMapper.selectById(any())).thenReturn(version("other-prompt", 1, "别人的正文"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.diff(prompt.getId(), prompt.getCurrentVersionId(), prompt.getCurrentVersionId()));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void diffProducesLineChangesForSamePrompt() {
        AgentPrompt prompt = prompt(0);
        when(promptMapper.selectById(any())).thenReturn(prompt);
        AgentPromptVersion from = version(prompt.getId(), 1, "第一行\n第二行");
        AgentPromptVersion to = version(prompt.getId(), 2, "第一行\n改过的第二行");
        when(versionMapper.selectById(from.getId())).thenReturn(from);
        when(versionMapper.selectById(to.getId())).thenReturn(to);

        List<TextDiff.DiffLine> lines = service.diff(prompt.getId(), from.getId(), to.getId());

        assertTrue(lines.stream().anyMatch(line -> "remove".equals(line.type()) || "add".equals(line.type())),
                "应出现新增或删除行：" + lines);
    }

    @Test
    void deleteRefusesWhenStrategyReferencesThePrompt() {
        AgentPrompt prompt = prompt(0);
        when(promptMapper.selectById(any())).thenReturn(prompt);
        when(strategyMapper.selectCount(any())).thenReturn(1L);

        ApiException error = assertThrows(ApiException.class, () -> service.delete(prompt.getId()));

        assertEquals(ErrorCode.CONFIG_IN_USE, error.errorCode());
        verify(promptMapper, never()).deleteById("never-called");
    }

    private static AgentPrompt prompt(int revision) {
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(com.codereview.agent.support.AgentIds.next());
        prompt.setName("通用规则");
        prompt.setPurpose("SPECIALIST");
        prompt.setRevision(revision);
        prompt.setCurrentVersionId(com.codereview.agent.support.AgentIds.next());
        return prompt;
    }

    private static AgentPromptVersion version(String promptId, int no, String content) {
        AgentPromptVersion version = new AgentPromptVersion();
        version.setId(com.codereview.agent.support.AgentIds.next());
        version.setPromptId(promptId);
        version.setVersionNo(no);
        version.setContent(content);
        return version;
    }
}
