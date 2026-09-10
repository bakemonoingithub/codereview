package com.codereview.dto;

import java.util.List;

public record ReviewTriggerReq(String branch, Long strategyId, List<String> scope, Boolean mergeFiles) {
}
