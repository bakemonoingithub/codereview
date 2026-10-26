package com.codereview.agent.tools;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code read_diff}：一个冻结比较关系的变更清单或逐 hunk 前后侧内容。
 *
 * <p>不带 {@code path} 时返回变更清单（最多 20 项）；带 {@code path} 时解析 hunk 头，
 * 用前后侧原始文件切片生成可引用材料 —— 行号来自真实文件，不拿 patch 行号顶替。
 * hunk 过大时不给截断首页，而是返回原始行范围并指引 {@code read_code}。
 */
@Component
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReadDiffTool {

    private final RepositoryReadService repository;
    private final ToolMaterialStore materials;

    public ReadDiffTool(RepositoryReadService repository, ToolMaterialStore materials) {
        this.repository = repository;
        this.materials = materials;
    }

    public ToolEnvelope execute(String toolCallId, ToolInvocation invocation, AgentToolScope scope) {
        AgentToolScope.Comparison comparison = scope.comparison(invocation.comparisonId());
        ToolCursor cursor = ToolCursor.decode(invocation.cursor(), invocation);
        return invocation.path() == null
                ? list(toolCallId, invocation, scope, comparison, cursor)
                : hunks(toolCallId, invocation, scope, comparison, cursor);
    }

    private ToolEnvelope list(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                              AgentToolScope.Comparison comparison, ToolCursor cursor) {
        CommitDetail detail = repository.commit(scope.projectId(), comparison.headSha());
        List<ChangedFile> files = detail.files() == null ? List.of() : detail.files();
        int offset = Math.min(cursor.offset(), files.size());
        List<Map<String, Object>> items = new ArrayList<>();
        int budget = scope.limits().getEnvelopeMaxBytes() - 512;
        int index = offset;
        while (index < files.size() && items.size() < scope.limits().getDiffMaxItems()) {
            ChangedFile file = files.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            if (file.previousPath() != null && !file.previousPath().isBlank()) {
                item.put("oldPath", file.previousPath());
            }
            item.put("status", file.status());
            if (file.additions() != null) {
                item.put("additions", file.additions());
            }
            if (file.deletions() != null) {
                item.put("deletions", file.deletions());
            }
            int cost = file.path().length() * 2 + 160;
            if (!items.isEmpty() && cost > budget) {
                break;
            }
            budget -= cost;
            items.add(item);
            index++;
        }

        boolean more = index < files.size();
        boolean truncated = detail.truncated();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comparisonId", comparison.id());
        data.put("headSha", comparison.headSha());
        data.put("baseSha", comparison.baseSha());
        data.put("totalFiles", files.size());
        data.put("items", items);
        data.put("hint", "读取某个文件的逐 hunk 内容时再次调用本工具并传入 path");
        String diagnostic = truncated ? "宿主返回的变更清单不完整" : null;
        return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), comparison.id(),
                List.of(), data,
                more || truncated ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE,
                more ? ToolCursor.encode(invocation, index) : null, diagnostic);
    }

    private ToolEnvelope hunks(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                               AgentToolScope.Comparison comparison, ToolCursor cursor) {
        RepositorySnapshots.DiffSides sides =
                repository.diff(scope.projectId(), comparison.headSha(), invocation.path());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comparisonId", comparison.id());
        data.put("path", sides.path());
        if (sides.oldPath() != null) {
            data.put("oldPath", sides.oldPath());
        }
        data.put("status", sides.status());
        data.put("headSha", sides.headSha());
        data.put("baseSha", sides.baseSha());

        if (sides.binary()) {
            data.put("note", "二进制文件没有可读文本差异");
            return new ToolEnvelope(toolCallId, ToolEnvelope.UNREADABLE, scope.versionRefs(),
                    comparison.id(), List.of(), data, ToolEnvelope.UNKNOWN, null,
                    "二进制文件不可作为文本材料：" + sides.path());
        }
        List<ToolPatch.Hunk> hunks = ToolPatch.parse(sides.patch());
        if (hunks.isEmpty()) {
            data.put("note", "宿主未提供逐 hunk 差异；请用 read_code 读取前后侧文件");
            data.put("beforeReadable", sides.before().present());
            data.put("afterReadable", sides.after().present());
            return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(),
                    comparison.id(), List.of(), data, ToolEnvelope.UNKNOWN, null,
                    "没有可解析的 hunk：patch 缺失");
        }

        int offset = Math.min(cursor.offset(), hunks.size());
        List<Map<String, Object>> items = new ArrayList<>();
        List<ToolEnvelope.ToolMaterial> materialsOut = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int budget = scope.limits().getEnvelopeMaxBytes() - 768;
        int index = offset;
        while (index < hunks.size() && items.size() < scope.limits().getDiffMaxHunks()) {
            ToolPatch.Hunk hunk = hunks.get(index);
            SideSlice before = slice(scope, sides, true, hunk);
            SideSlice after = slice(scope, sides, false, hunk);
            boolean oversized = before.oversized() || after.oversized();
            int cost = 240 + hunk.header().length()
                    + before.cost() + after.cost();
            if (!items.isEmpty() && cost > budget) {
                break;
            }
            budget -= cost;

            List<String> ids = new ArrayList<>();
            for (SideSlice slice : List.of(before, after)) {
                if (slice.content() == null) {
                    continue;
                }
                ToolEnvelope.ToolMaterial saved = materials.save(scope.projectId(),
                        new ToolEnvelope.ToolMaterial(null, slice.path(), slice.side(), slice.sha(),
                                slice.startLine(), slice.endLine(),
                                ToolTexts.sha256(slice.content()), ToolEnvelope.COMPLETE,
                                slice.content()));
                materialsOut.add(saved);
                ids.add(saved.materialId());
            }
            if (oversized) {
                notes.add("hunk " + hunk.index() + " 的某一侧超过材料上限，请按行范围使用 read_code");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hunk", hunk.index());
            if (!hunk.header().isBlank()) {
                item.put("header", hunk.header());
            }
            item.put("beforeStart", hunk.beforeStart());
            item.put("beforeLines", hunk.beforeCount());
            item.put("afterStart", hunk.afterStart());
            item.put("afterLines", hunk.afterCount());
            item.put("materialIds", ids);
            items.add(item);
            index++;
        }

        boolean more = index < hunks.size();
        boolean incomplete = notes.size() > 0;
        data.put("hunks", items);
        data.put("totalHunks", hunks.size());
        return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), comparison.id(),
                materialsOut, data,
                more || incomplete ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE,
                more ? ToolCursor.encode(invocation, index) : null,
                notes.isEmpty() ? null : String.join("；", notes));
    }

    /**
     * 按 hunk 头给出的原始行范围从该侧全文切片。
     *
     * <p>该侧不存在、该侧无内容或切片超过单条材料上限时返回 {@code content=null}：
     * 不把半个 hunk 当作可引用材料，模型应改用 {@code read_code} 精确读取。
     */
    private static SideSlice slice(AgentToolScope scope, RepositorySnapshots.DiffSides sides, boolean before,
                                   ToolPatch.Hunk hunk) {
        int count = before ? hunk.beforeCount() : hunk.afterCount();
        if (count <= 0) {
            return SideSlice.empty();
        }
        RepositorySnapshots.Side side = before ? sides.before() : sides.after();
        String sha = before ? sides.baseSha() : sides.headSha();
        if (!side.present() || sha == null) {
            return SideSlice.empty();
        }
        String[] lines = ToolTexts.splitLines(side.content());
        int start = before ? hunk.beforeStart() : hunk.afterStart();
        int end = Math.min(lines.length, before ? hunk.beforeEnd() : hunk.afterEnd());
        if (start > end || start > lines.length) {
            return SideSlice.empty();
        }
        String content = ToolTexts.joinLines(lines, start, end);
        String path = before && sides.oldPath() != null ? sides.oldPath() : sides.path();
        if (ToolTexts.utf8Bytes(content) > scope.limits().getMaterialMaxBytes()) {
            return new SideSlice(path, before ? "BEFORE" : "AFTER", sha, start, end, null, true);
        }
        return new SideSlice(path, before ? "BEFORE" : "AFTER", sha, start, end, content, false);
    }

    private record SideSlice(String path, String side, String sha, int startLine, int endLine,
                             String content, boolean oversized) {

        static SideSlice empty() {
            return new SideSlice(null, null, null, 0, 0, null, false);
        }

        int cost() {
            return content == null ? 0 : ToolTexts.utf8Bytes(content) + 160;
        }
    }
}
