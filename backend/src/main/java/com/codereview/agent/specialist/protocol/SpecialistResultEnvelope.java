package com.codereview.agent.specialist.protocol;

import java.util.List;

/**
 * 服务端结果封套。
 *
 * <p>{@code id/taskId/targetId/kind/schemaVersion/versions/completeness/materialGaps/
 * sourceCallIds} 由服务端填写，模型不能指定。正文仍是模型结果，位置和材料引用已通过
 * {@link SpecialistResultValidator} 校验；封套与结果一起保存后不可变。
 */
public record SpecialistResultEnvelope(String id, String taskId, String targetId, String kind,
                                       String schemaVersion, Versions versions,
                                       String completeness, List<String> materialGaps,
                                       List<String> sourceCallIds,
                                       SpecialistResults.Result result) {

    public SpecialistResultEnvelope {
        if (id == null || id.isBlank() || taskId == null || taskId.isBlank()
                || targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("结果封套缺少稳定主体");
        }
        if (kind == null || kind.isBlank() || schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("结果封套缺少类型或协议版本");
        }
        materialGaps = materialGaps == null ? List.of() : List.copyOf(materialGaps);
        sourceCallIds = sourceCallIds == null ? List.of() : List.copyOf(sourceCallIds);
    }

    /** 固定版本与前后侧；{@code oldPath} 只用于重命名差异。 */
    public record Versions(String headSha, String baseSha, String path, String oldPath,
                           String side) {
    }
}
