package com.codereview.agent.tools;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadOnlyToolServiceTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    private RepositoryReadService repository;
    private ReadOnlyToolService service;
    private final AgentToolScope scope = AgentToolScope.single("p1", SHA);

    @BeforeEach
    void setUp() {
        repository = mock(RepositoryReadService.class);
        service = new ReadOnlyToolService(new ObjectMapper(), repository,
                mock(ReadCodeTool.class), mock(SearchCodeTool.class), mock(ReadDiffTool.class));
        when(repository.listPaths("p1", SHA)).thenReturn(paths(false));
    }

    @Test
    void treeListsDirectEntriesWithChildCounts() {
        ToolEnvelope envelope = service.execute("call-1", AgentToolNames.TREE,
                "{\"version\":\"V1\"}", scope);

        assertEquals(ToolEnvelope.OK, envelope.status());
        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        List<Map<String, Object>> entries = entries(envelope);
        assertEquals(List.of("README.md", "src", "src/A.java", "src/x"),
                entries.stream().map(item -> item.get("path")).toList());
        Map<String, Object> src = entries.get(1);
        assertEquals("tree", src.get("type"));
        assertEquals(1, src.get("depth"));
        assertEquals(3, src.get("childCount"));
        assertEquals(true, entries.get(0).get("reviewable"));
    }

    @Test
    void treeExpandsDeeperOnlyWhenAsked() {
        ToolEnvelope deep = service.execute("call-1", AgentToolNames.TREE,
                "{\"version\":\"V1\",\"depth\":3}", scope);

        assertEquals(List.of("README.md", "src", "src/A.java", "src/x", "src/x/B.java"),
                entries(deep).stream().map(item -> item.get("path")).toList());
        assertEquals(3, entries(deep).get(4).get("depth"));
    }

    @Test
    void treeStartsFromTheRequestedDirectory() {
        ToolEnvelope envelope = service.execute("call-1", AgentToolNames.TREE,
                "{\"version\":\"V1\",\"path\":\"src\",\"depth\":1}", scope);

        assertEquals(List.of("src/A.java", "src/x"),
                entries(envelope).stream().map(item -> item.get("path")).toList());
        assertEquals(1, entries(envelope).get(0).get("depth"));
    }

    @Test
    void missingDirectoryAndFilePathsAreReportedPrecisely() {
        assertEquals(ToolEnvelope.NOT_FOUND, service.execute("call-1", AgentToolNames.TREE,
                "{\"version\":\"V1\",\"path\":\"nope\"}", scope).status());
        assertEquals(ToolEnvelope.INVALID_ARGUMENT, service.execute("call-1", AgentToolNames.TREE,
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}", scope).status());
    }

    @Test
    void findMatchesGlobAndPaginatesThroughCursor() {
        ToolEnvelope first = service.execute("call-1", AgentToolNames.FIND_FILES,
                "{\"version\":\"V1\",\"glob\":\"**/*.java\",\"limit\":1}", scope);

        assertEquals(ToolEnvelope.PARTIAL, first.completeness());
        assertEquals(List.of("src/A.java"), ((Map<?, ?>) first.data()).get("matches"));
        assertEquals(2, ((Map<?, ?>) first.data()).get("totalMatches"));
        assertNotNull(first.nextCursor());

        ToolEnvelope second = service.execute("call-1", AgentToolNames.FIND_FILES,
                "{\"version\":\"V1\",\"glob\":\"**/*.java\",\"limit\":1,\"cursor\":\""
                        + first.nextCursor() + "\"}", scope);

        assertEquals(ToolEnvelope.COMPLETE, second.completeness());
        assertEquals(List.of("src/x/B.java"), ((Map<?, ?>) second.data()).get("matches"));
        assertNull(second.nextCursor());
    }

    @Test
    void truncatedPathListsNeverClaimCompleteness() {
        when(repository.listPaths("p1", SHA)).thenReturn(paths(true));

        ToolEnvelope envelope = service.execute("call-1", AgentToolNames.FIND_FILES,
                "{\"version\":\"V1\",\"glob\":\"**/*.java\"}", scope);

        assertEquals(ToolEnvelope.UNKNOWN, envelope.completeness());
        assertTrue(envelope.diagnostic().contains("不完整"));
    }

    @Test
    void unknownToolAndBadArgumentsBecomeInvalidArgumentEnvelopes() {
        assertEquals(ToolEnvelope.INVALID_ARGUMENT, service.execute("call-1", "rm_rf", "{}", scope).status());
        assertEquals(ToolEnvelope.INVALID_ARGUMENT, service.execute("call-1", AgentToolNames.TREE,
                "{\"version\":\"V9\"}", scope).status());
    }

    @Test
    void envelopesStayWithinTheSerializedSizeLimit() {
        List<RepositorySnapshots.PathEntry> many = new java.util.ArrayList<>();
        for (int index = 0; index < 400; index++) {
            many.add(new RepositorySnapshots.PathEntry("dir" + index + "/File" + index + ".java",
                    "blob", true));
        }
        when(repository.listPaths("p1", SHA)).thenReturn(
                new RepositorySnapshots.PathList(many, SHA, false));

        ToolEnvelope envelope = service.execute("call-1", AgentToolNames.TREE,
                "{\"version\":\"V1\",\"depth\":2,\"limit\":200}", scope);

        int size = ToolEnvelopes.size(new ObjectMapper(), envelope);
        assertTrue(size <= ToolLimits.ENVELOPE_MAX_BYTES, "size=" + size);
        assertTrue(entries(envelope).size() <= ToolLimits.TREE_MAX_LIMIT);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(ToolEnvelope envelope) {
        return (List<Map<String, Object>>) ((Map<?, ?>) envelope.data()).get("entries");
    }

    private static RepositorySnapshots.PathList paths(boolean truncated) {
        return new RepositorySnapshots.PathList(List.of(
                new RepositorySnapshots.PathEntry("README.md", "blob", true),
                new RepositorySnapshots.PathEntry("src", "tree", false),
                new RepositorySnapshots.PathEntry("src/A.java", "blob", true),
                new RepositorySnapshots.PathEntry("src/x", "tree", false),
                new RepositorySnapshots.PathEntry("src/x/B.java", "blob", true)
        ), SHA, truncated);
    }
}
