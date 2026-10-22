package com.codereview.agent.tools;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadDiffToolTest {

    private static final String HEAD = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String BASE = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    private static final String PATH = "src/A.java";

    @Mock
    private RepositoryReadService repository;
    @Mock
    private ToolMaterialStore materialStore;

    private ReadDiffTool tool;
    private final AgentToolScope scope = new AgentToolScope("p1", Map.of("V1", HEAD),
            AgentToolScope.comparisonsOf(new AgentToolScope.Comparison("C1", HEAD, BASE)));

    @BeforeEach
    void setUp() {
        tool = new ReadDiffTool(repository, materialStore);
        when(materialStore.save(anyString(), any())).thenAnswer(invocation -> {
            ToolEnvelope.ToolMaterial draft = invocation.getArgument(1);
            return new ToolEnvelope.ToolMaterial("m-" + draft.side() + "-" + draft.startLine(),
                    draft.path(), draft.side(), draft.sha(), draft.startLine(), draft.endLine(),
                    draft.contentHash(), draft.completeness(), draft.content());
        });
    }

    @Test
    void listsChangedFilesInPagesOfTwenty() {
        when(repository.commit("p1", HEAD)).thenReturn(detail(25, false));

        ToolEnvelope envelope = tool.execute("call-1", invocation(null), scope);

        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<?, ?>) envelope.data()).get("items");
        assertEquals(20, items.size());
        assertEquals(25, ((Map<?, ?>) envelope.data()).get("totalFiles"));
        assertEquals(ToolEnvelope.PARTIAL, envelope.completeness());
        assertNotNull(envelope.nextCursor());
    }

    @Test
    void truncatedCommitListingNeverClaimsCompleteness() {
        when(repository.commit("p1", HEAD)).thenReturn(detail(2, true));

        ToolEnvelope envelope = tool.execute("call-1", invocation(null), scope);

        assertEquals(ToolEnvelope.PARTIAL, envelope.completeness());
        assertTrue(envelope.diagnostic().contains("不完整"));
    }

    @Test
    void readsHunkSidesWithOriginalLineNumbers() {
        String patch = "@@ -10,2 +10,2 @@ class A {\n-old\n+new\n context\n";
        when(repository.diff("p1", HEAD, PATH)).thenReturn(sides(patch));

        ToolEnvelope envelope = tool.execute("call-1", invocation(PATH), scope);

        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        assertEquals(2, envelope.materials().size());
        ToolEnvelope.ToolMaterial before = envelope.materials().get(0);
        assertEquals("BEFORE", before.side());
        assertEquals(BASE, before.sha());
        assertEquals(10, before.startLine());
        assertEquals(11, before.endLine());
        ToolEnvelope.ToolMaterial after = envelope.materials().get(1);
        assertEquals("AFTER", after.side());
        assertEquals(HEAD, after.sha());
        assertEquals(10, after.startLine());
    }

    @Test
    void paginatesHunksBeyondTen() {
        String patch = hunks(12);
        when(repository.diff("p1", HEAD, PATH)).thenReturn(sides(patch));

        ToolEnvelope first = tool.execute("call-1", invocation(PATH), scope);

        assertEquals(ToolEnvelope.PARTIAL, first.completeness());
        assertEquals(10, ((List<?>) ((Map<?, ?>) first.data()).get("hunks")).size());
        assertNotNull(first.nextCursor());

        ToolEnvelope second = tool.execute("call-1", new ToolInvocation(AgentToolNames.READ_DIFF,
                null, null, "C1", PATH, null, ToolLimits.DIFF_MAX_HUNKS, first.nextCursor(),
                null, null, List.of(), null, null), scope);
        assertEquals(ToolEnvelope.COMPLETE, second.completeness());
        assertEquals(2, ((List<?>) ((Map<?, ?>) second.data()).get("hunks")).size());
        assertNull(second.nextCursor());
    }

    @Test
    void binaryFilesAreExplicitlyUnreadable() {
        when(repository.diff("p1", HEAD, PATH)).thenReturn(new RepositorySnapshots.DiffSides(
                HEAD, BASE, PATH, null, "modified", "Binary files differ",
                RepositorySnapshots.Side.unreadable("BINARY"),
                RepositorySnapshots.Side.unreadable("BINARY")));

        ToolEnvelope envelope = tool.execute("call-1", invocation(PATH), scope);

        assertEquals(ToolEnvelope.UNREADABLE, envelope.status());
        assertEquals(ToolEnvelope.UNKNOWN, envelope.completeness());
        assertTrue(envelope.materials().isEmpty());
    }

    @Test
    void missingPatchExplainsTheGapInsteadOfGuessing() {
        when(repository.diff("p1", HEAD, PATH)).thenReturn(new RepositorySnapshots.DiffSides(
                HEAD, BASE, PATH, null, "modified", null,
                RepositorySnapshots.Side.present("a\nb\n"), RepositorySnapshots.Side.present("a\nc\n")));

        ToolEnvelope envelope = tool.execute("call-1", invocation(PATH), scope);

        assertEquals(ToolEnvelope.OK, envelope.status());
        assertEquals(ToolEnvelope.UNKNOWN, envelope.completeness());
        assertTrue(envelope.diagnostic().contains("patch"));
        assertTrue(((Map<?, ?>) envelope.data()).containsKey("beforeReadable"));
    }

    private ToolInvocation invocation(String path) {
        return new ToolInvocation(AgentToolNames.READ_DIFF, null, null, "C1", path, null,
                ToolLimits.DIFF_MAX_HUNKS, null, null, null, List.of(), null, null);
    }

    private static CommitDetail detail(int files, boolean truncated) {
        List<ChangedFile> changed = new ArrayList<>();
        for (int index = 0; index < files; index++) {
            changed.add(new ChangedFile("src/File" + index + ".java", null,
                    index % 3 == 0 ? "added" : "modified", 1, 0, 1, "patch"));
        }
        return new CommitDetail(HEAD, List.of(BASE), "msg", "author", "today", 1, 1, 2,
                truncated, changed);
    }

    private static RepositorySnapshots.DiffSides sides(String patch) {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int line = 1; line <= 40; line++) {
            before.append("before line ").append(line).append('\n');
            after.append("after line ").append(line).append('\n');
        }
        return new RepositorySnapshots.DiffSides(HEAD, BASE, PATH, null, "modified", patch,
                RepositorySnapshots.Side.present(before.toString()),
                RepositorySnapshots.Side.present(after.toString()));
    }

    private static String hunks(int count) {
        StringBuilder patch = new StringBuilder();
        for (int index = 0; index < count; index++) {
            int start = index * 2 + 1;
            patch.append("@@ -").append(start).append(",1 +").append(start).append(",1 @@\n");
            patch.append("-old\n+new\n");
        }
        return patch.toString();
    }
}
