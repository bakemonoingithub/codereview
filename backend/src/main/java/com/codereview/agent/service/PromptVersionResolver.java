package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.support.AgentIds;

import java.util.Map;
import java.util.Objects;

/** 解析所选正文版本；仅未指定版本时使用当前版本，不对失效版本回退。 */
final class PromptVersionResolver {
    private PromptVersionResolver() {
    }

    static AgentPromptVersion resolve(AgentDatabase db, AgentPrompt prompt, String versionId) {
        String selectedId = versionId == null ? prompt.getCurrentVersionId() : versionId;
        if (selectedId == null || selectedId.isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "提示词没有可用的正文版本");
        }
        AgentPromptVersion version = db.mapper(AgentPromptVersionMapper.class)
                .selectById(AgentIds.requireValid(selectedId, "提示词版本 ID"));
        if (version == null || !Objects.equals(version.getPromptId(), prompt.getId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "所选版本不存在或不属于该提示词，请重新选择",
                    Map.of("promptId", prompt.getId(), "versionId", selectedId));
        }
        if (version.getContent() == null || version.getContent().isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "所选提示词版本缺少正文");
        }
        return version;
    }
}
