package com.codereview.agent.support;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.mapper.AgentPromptVersionMapper;

import java.util.Map;
import java.util.Objects;

/**
 * 解析提示词正文版本：版本存在、归属于所选提示词且正文非空。
 *
 * <p>专项与 Agent 在版本 ID 为 null 时可使用当前版本；报告等显式选择入口使用
 * {@link #resolveExplicit}，不隐式回退当前版本。正文为空时的错误码由调用入口显式传入，
 * 不把场景差异藏进共同默认值。
 *
 * <p>维护关联 BRC-M07：各入口的默认、不选规则场景和空正文错误分类仍由
 * TaskService、AgentTaskService、ReportGenerationService 显式决定；修改时核对
 * PromptVersionResolverTest 及 maintenance-map.md。
 */
public final class PromptVersionResolver {

    private PromptVersionResolver() {
    }

    /** 未指定版本时使用提示词当前版本；显式空白或失效版本不回退。 */
    public static AgentPromptVersion resolve(AgentDatabase db, AgentPrompt prompt, String versionId) {
        String selectedId = versionId == null ? prompt.getCurrentVersionId() : versionId;
        if (selectedId == null || selectedId.isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "提示词没有可用的正文版本");
        }
        AgentPromptVersion version = requireOwned(db, prompt, selectedId);
        requireContent(version, ErrorCode.CONFIG_INCOMPLETE, "所选提示词版本缺少正文");
        return version;
    }

    /** 调用方已明确选择版本；不回退当前版本，正文为空的错误分类由入口给出。 */
    public static AgentPromptVersion resolveExplicit(AgentDatabase db, AgentPrompt prompt, String versionId,
                                                     ErrorCode blankContentCode, String blankContentMessage) {
        AgentPromptVersion version = requireOwned(db, prompt, versionId);
        requireContent(version, blankContentCode, blankContentMessage);
        return version;
    }

    private static AgentPromptVersion requireOwned(AgentDatabase db, AgentPrompt prompt, String versionId) {
        AgentPromptVersion version = db.mapper(AgentPromptVersionMapper.class)
                .selectById(AgentIds.requireValid(versionId, "提示词版本 ID"));
        if (version == null || !Objects.equals(version.getPromptId(), prompt.getId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "所选版本不存在或不属于该提示词，请重新选择",
                    Map.of("promptId", prompt.getId(), "versionId", versionId));
        }
        return version;
    }

    private static void requireContent(AgentPromptVersion version, ErrorCode errorCode, String message) {
        if (version.getContent() == null || version.getContent().isBlank()) {
            throw new ApiException(errorCode, message);
        }
    }
}
