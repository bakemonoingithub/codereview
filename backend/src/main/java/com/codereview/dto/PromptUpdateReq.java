package com.codereview.dto;

import java.util.List;

public record PromptUpdateReq(String name, String description, List<String> tags) {
}
