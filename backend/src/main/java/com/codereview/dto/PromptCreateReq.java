package com.codereview.dto;

import java.util.List;

public record PromptCreateReq(String name, String description, List<String> tags, String content) {
}
