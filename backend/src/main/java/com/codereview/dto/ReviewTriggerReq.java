package com.codereview.dto;

import java.util.List;

public record ReviewTriggerReq(String branch, List<String> scope) {
}
