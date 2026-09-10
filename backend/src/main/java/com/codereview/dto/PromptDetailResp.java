package com.codereview.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PromptDetailResp(Long id, String name, String description, List<String> tags,
                               Long currentVersionId, Integer currentVersionNo, String currentContent,
                               List<VersionResp> versions) {
    public record VersionResp(Long id, Integer versionNo, LocalDateTime createdAt) {
    }
}
