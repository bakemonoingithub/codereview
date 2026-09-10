package com.codereview.dto;

import java.util.List;

/**
 * 触发审查入参。
 * <p>
 * {@code commitSha} 仅 diff 审查（analyzer_type=5）必填：diff 审查必须锚定到具体提交，
 * 否则就会重现「选了历史提交、实际却审分支最新代码」的老问题。
 */
public record ReviewTriggerReq(String branch, Long strategyId, List<String> scope, Boolean mergeFiles, String commitSha) {
}
