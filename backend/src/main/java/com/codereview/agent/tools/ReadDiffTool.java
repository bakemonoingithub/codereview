package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.FixedComparison;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
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
    private final ObjectMapper mapper;

    public ReadDiffTool(RepositoryReadService repository, ToolMaterialStore materials, ObjectMapper mapper) {
        this.repository = repository;
        this.materials = materials;
        this.mapper = mapper;
    }

    public ToolEnvelope execute(String toolCallId, ToolInvocation invocation, AgentToolScope scope) {
        AgentToolScope.Comparison comparison = scope.comparison(invocation.comparisonId());
        ToolCursor cursor = ToolCursor.decode(invocation.cursor(), invocation);
        if (comparison.range()) {
            return invocation.path() == null
                    ? listRange(toolCallId, invocation, scope, comparison, cursor)
                    : hunksRange(toolCallId, invocation, scope, comparison, cursor);
        }
        return invocation.path() == null
                ? list(toolCallId, invocation, scope, comparison, cursor)
                : hunks(toolCallId, invocation, scope, comparison, cursor);
    }

    private ToolEnvelope listRange(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                                   AgentToolScope.Comparison comparison, ToolCursor cursor) {
        FixedComparison.Result result = repository.frozenRange(scope.projectId(),
                comparison.baseSha(), comparison.headSha());
        List<Map<String, Object>> all = new ArrayList<>();
        for (FixedComparison.FileChange file : result.files()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            if (file.oldPath() != null && !file.oldPath().isBlank()) {
                item.put("oldPath", file.oldPath());
            }
            item.put("status", file.status());
            item.put("category", file.category());
            if (file.reason() != null) {
                item.put("reason", file.reason());
            }
            all.add(item);
        }
        boolean truncated = !result.complete();
        return page(toolCallId, invocation, scope, comparison, cursor, all,
                truncated ? result.fileCount() : all.size(), truncated,
                "净变化清单不完整，不能把已返回的部分当成全部变更",
                "读取某个文本文件的逐 hunk 内容时再次调用本工具并传入 path；二进制和无法读取的文件没有审查");
    }

    private ToolEnvelope hunksRange(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                                    AgentToolScope.Comparison comparison, ToolCursor cursor) {
        try {
            RepositorySnapshots.DiffSides sides = repository.diffRange(scope.projectId(),
                    comparison.baseSha(), comparison.headSha(), invocation.path());
            if (failed(sides.before()) || failed(sides.after())) {
                return unreadSides(toolCallId, scope, comparison, sides);
            }
            String patch = sides.patch();
            if (patch == null || patch.isBlank()) {
                patch = unified(sideText(sides.before()), sideText(sides.after()));
            }
            return hunksFrom(toolCallId, invocation, scope, comparison, cursor,
                    new RepositorySnapshots.DiffSides(sides.headSha(), sides.baseSha(), sides.path(),
                            sides.oldPath(), sides.status(), patch, sides.before(), sides.after()));
        } catch (ApiException failure) {
            if (failure.errorCode() != ErrorCode.FILE_NOT_READABLE) {
                throw failure;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("comparisonId", comparison.id());
            data.put("path", invocation.path());
            data.put("headSha", comparison.headSha());
            data.put("baseSha", comparison.baseSha());
            data.put("note", "该净变化文件没有可读文本差异");
            return new ToolEnvelope(toolCallId, ToolEnvelope.UNREADABLE, scope.versionRefs(),
                    comparison.id(), List.of(), data, ToolEnvelope.UNKNOWN, null, failure.getMessage());
        }
    }

    private ToolEnvelope list(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                              AgentToolScope.Comparison comparison, ToolCursor cursor) {
        CommitDetail detail = repository.commit(scope.projectId(), comparison.headSha());
        List<ChangedFile> files = detail.files() == null ? List.of() : detail.files();
        List<Map<String, Object>> all = new ArrayList<>();
        for (ChangedFile file : files) {
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
            all.add(item);
        }
        return page(toolCallId, invocation, scope, comparison, cursor, all, all.size(),
                detail.truncated(), "宿主返回的变更清单不完整",
                "读取某个文件的逐 hunk 内容时再次调用本工具并传入 path");
    }

    /**
     * 按序列化后的整页字节分页。估算路径长度会漏掉旧路径和 JSON 开销，
     * 整页超限时外层会拒绝发送，模型也就拿不到下一页游标。
     */
    private ToolEnvelope page(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                              AgentToolScope.Comparison comparison, ToolCursor cursor,
                              List<Map<String, Object>> all, int totalFiles, boolean sourceTruncated,
                              String truncatedDiagnostic, String hint) {
        int offset = Math.min(cursor.offset(), all.size());
        List<Map<String, Object>> items = new ArrayList<>();
        int index = offset;
        while (index < all.size() && items.size() < scope.limits().getDiffMaxItems()) {
            items.add(all.get(index));
            boolean moreAfter = index + 1 < all.size();
            ToolEnvelope candidate = listed(toolCallId, scope, comparison, items, totalFiles, hint,
                    moreAfter, sourceTruncated, moreAfter ? index + 1 : offset, truncatedDiagnostic, invocation);
            if (ToolEnvelopes.size(mapper, candidate) <= scope.limits().getEnvelopeMaxBytes()) {
                index++;
                continue;
            }
            items.remove(items.size() - 1);
            if (!items.isEmpty()) {
                break;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("comparisonId", comparison.id());
            data.put("path", all.get(index).get("path"));
            data.put("note", "单个变更项无法装入工具返回上限");
            return new ToolEnvelope(toolCallId, ToolEnvelope.ERROR, scope.versionRefs(), comparison.id(),
                    List.of(), data, ToolEnvelope.UNKNOWN, null,
                    "单个变更项超过工具返回上限，请改用 read_code 按路径读取");
        }
        boolean more = index < all.size();
        return listed(toolCallId, scope, comparison, items, totalFiles, hint, more, sourceTruncated,
                index, truncatedDiagnostic, invocation);
    }

    private ToolEnvelope listed(String toolCallId, AgentToolScope scope, AgentToolScope.Comparison comparison,
                                List<Map<String, Object>> items, int totalFiles, String hint, boolean more,
                                boolean sourceTruncated, int nextOffset, String truncatedDiagnostic,
                                ToolInvocation invocation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comparisonId", comparison.id());
        data.put("headSha", comparison.headSha());
        data.put("baseSha", comparison.baseSha());
        data.put("totalFiles", totalFiles);
        data.put("items", items);
        data.put("hint", hint);
        return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), comparison.id(),
                List.of(), data,
                more || sourceTruncated ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE,
                more ? ToolCursor.encode(invocation, nextOffset) : null,
                sourceTruncated ? truncatedDiagnostic : null);
    }

    private ToolEnvelope unreadSides(String toolCallId, AgentToolScope scope,
                                     AgentToolScope.Comparison comparison, RepositorySnapshots.DiffSides sides) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comparisonId", comparison.id());
        data.put("path", sides.path());
        data.put("headSha", sides.headSha());
        data.put("baseSha", sides.baseSha());
        data.put("status", sides.status());
        data.put("beforeStatus", sides.before().status());
        data.put("beforeReason", sides.before().reason());
        data.put("afterStatus", sides.after().status());
        data.put("afterReason", sides.after().reason());
        data.put("note", "有一侧读取失败，不能把未读到的内容当成新增或删除");
        return new ToolEnvelope(toolCallId, ToolEnvelope.UNREADABLE, scope.versionRefs(), comparison.id(),
                List.of(), data, ToolEnvelope.UNKNOWN, null, failureText(sides));
    }

    private static String failureText(RepositorySnapshots.DiffSides sides) {
        StringBuilder text = new StringBuilder();
        appendFailure(text, "修改前", sides.before());
        appendFailure(text, "修改后", sides.after());
        text.append("不能据此判断文件是本次新增或删除。");
        return text.toString();
    }

    private static void appendFailure(StringBuilder text, String name, RepositorySnapshots.Side side) {
        if (side != null && RepositorySnapshots.Side.UNREADABLE.equals(side.status())) {
            text.append(name).append("读取失败：").append(side.reason() == null ? "READ_FAILED" : side.reason()).append("。");
        }
    }

    private static boolean failed(RepositorySnapshots.Side side) {
        return side == null || RepositorySnapshots.Side.UNREADABLE.equals(side.status());
    }

    private ToolEnvelope hunks(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                               AgentToolScope.Comparison comparison, ToolCursor cursor) {
        return hunksFrom(toolCallId, invocation, scope, comparison, cursor,
                repository.diff(scope.projectId(), comparison.headSha(), invocation.path()));
    }

    /** 只有确实不存在的一侧才能当成空文本；读取失败不得走这里。 */
    private static String sideText(RepositorySnapshots.Side side) {
        if (side != null && side.present()) {
            return side.content() == null ? "" : side.content();
        }
        return "";
    }

    private static String unified(String before, String after) {
        try {
            RawText left = new RawText((before == null ? "" : before).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            RawText right = new RawText((after == null ? "" : after).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, left, right);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.format(edits, left, right);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            return "";
        }
    }

    private ToolEnvelope hunksFrom(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                                   AgentToolScope.Comparison comparison, ToolCursor cursor,
                                   RepositorySnapshots.DiffSides sides) {
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
