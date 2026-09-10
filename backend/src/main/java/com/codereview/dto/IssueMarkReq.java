package com.codereview.dto;

/**
 * 标记请求体。
 *
 * @param unitPath   issue 所在单元的文件路径
 * @param issueIndex issue 在该单元 issues 数组中的下标
 * @param markValue  0 未标记（撤销）/ 1 误报 / 2 已采纳
 */
public record IssueMarkReq(String unitPath, Integer issueIndex, Integer markValue) {
}
