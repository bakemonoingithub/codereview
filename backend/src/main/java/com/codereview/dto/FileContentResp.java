package com.codereview.dto;

/**
 * 文件查看响应（{@code GET /api/projects/{id}/file}）。
 *
 * @param mode       查询模式：{@code content} 文件原文 / {@code diff} 该提交的变更差异
 * @param path       仓库内相对路径
 * @param ref        取值引用：{@code content} 模式是分支名或 sha，{@code diff} 模式必须是 sha
 * @param content    文本内容；{@code diff} 模式下宿主没给 patch 时为空串（界面据此提示"无可显示差异"）
 * @param truncated  是否被截断（行数或字节任一超限）
 * @param totalLines 截断**前**的总行数，供界面说明"共 N 行"
 */
public record FileContentResp(String mode, String path, String ref, String content,
                              boolean truncated, int totalLines) {
}
