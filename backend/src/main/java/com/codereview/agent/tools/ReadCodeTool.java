package com.codereview.agent.tools;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code read_code}：按原始行号读取固定版本的一段文件。
 *
 * <p>同时受 400 行与 24 KiB 两个上限约束；先按行裁剪再按字节裁剪，返回实际末行，
 * 下一页从实际末行+1 继续 —— 不跳过尚未返回的行，也不把请求的末行当成已读末行。
 */
@Component
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReadCodeTool {

    private final RepositoryReadService repository;
    private final ToolMaterialStore materials;

    public ReadCodeTool(RepositoryReadService repository, ToolMaterialStore materials) {
        this.repository = repository;
        this.materials = materials;
    }

    public ToolEnvelope execute(String toolCallId, ToolInvocation invocation, AgentToolScope scope) {
        RepositorySnapshots.FileSnapshot file =
                repository.readFile(scope.projectId(), invocation.sha(), invocation.path());
        String[] lines = ToolTexts.splitLines(file.content());
        int total = lines.length;
        int start = invocation.start() == null ? 1 : invocation.start();
        int requestedEnd = invocation.end() == null ? start + scope.limits().getReadDefaultLines() - 1
                : invocation.end();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", file.path());
        data.put("sha", file.sha());
        data.put("totalLines", total);
        List<ToolEnvelope.ToolMaterial> materialsOut = new ArrayList<>();

        if (total == 0 || start > total) {
            data.put("startLine", start);
            data.put("endLine", total);
            data.put("contentHash", null);
            data.put("nextStartLine", null);
            String diagnostic = total == 0 ? "文件为空" : "起始行超出文件末尾（共 " + total + " 行）";
            return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), null,
                    List.of(), data, ToolEnvelope.COMPLETE, null, diagnostic);
        }

        int lineCap = start + scope.limits().getReadMaxLines() - 1;
        boolean cappedByLineLimit = requestedEnd > lineCap;
        int desiredEnd = Math.min(requestedEnd, total);
        int end = Math.min(desiredEnd, lineCap);
        String content = ToolTexts.joinLines(lines, start, end);
        while (ToolTexts.utf8Bytes(content) > scope.limits().getMaterialMaxBytes() && end > start) {
            end--;
            content = ToolTexts.joinLines(lines, start, end);
        }
        boolean clampedSingleLine = ToolTexts.utf8Bytes(content) > scope.limits().getMaterialMaxBytes();
        if (clampedSingleLine) {
            content = ToolTexts.clampToBytes(content, scope.limits().getMaterialMaxBytes());
        }
        boolean partial = end < desiredEnd || cappedByLineLimit || clampedSingleLine;
        Integer nextStartLine = end < total ? end + 1 : null;
        String contentHash = ToolTexts.sha256(content);

        ToolEnvelope.ToolMaterial material = materials.save(scope.projectId(),
                new ToolEnvelope.ToolMaterial(null, file.path(), "FILE", file.sha(), start, end,
                        contentHash, partial ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE, content));
        materialsOut.add(material);

        data.put("startLine", start);
        data.put("endLine", end);
        data.put("contentHash", contentHash);
        data.put("nextStartLine", nextStartLine);
        List<String> notes = new ArrayList<>();
        if (cappedByLineLimit) {
            notes.add("请求范围超过单次 " + scope.limits().getReadMaxLines() + " 行上限，已返回前 "
                    + (end - start + 1) + " 行");
        }
        if (clampedSingleLine) {
            notes.add("单行超过材料字节上限，已截短");
        }
        return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), null,
                materialsOut, data, partial ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE,
                null, notes.isEmpty() ? null : String.join("；", notes));
    }
}
