package com.codereview.agent.tools;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.git.GitMirror;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchCodePaginationTest {
    private static final String SHA = "a".repeat(40);
    private final ObjectMapper mapper = new ObjectMapper();
    private RepositoryReadService repository;
    private ToolMaterialStore materials;
    private GitGrepRunner grep;
    private ReadOnlyToolService service;

    @BeforeEach
    void setUp() {
        repository = mock(RepositoryReadService.class);
        materials = mock(ToolMaterialStore.class);
        grep = mock(GitGrepRunner.class);
        GitMirror mirror = mock(GitMirror.class);
        when(repository.mirrorTarget("p1")).thenReturn(new RepositoryReadService.MirrorTarget(
                "p1", "https://example.test/repo", null, 1, "p1|r1"));
        when(mirror.using(anyString(), any(), any(), anyList(), any(), any()))
                .thenAnswer(i -> ((GitMirror.Work<?>) i.getArgument(5)).run(new File(".")));
        when(materials.save(eq("p1"), any())).thenAnswer(i -> {
            ToolEnvelope.ToolMaterial draft = i.getArgument(1);
            String id = ToolTexts.sha256(draft.path() + draft.startLine() + draft.contentHash()).substring(0, 26);
            return new ToolEnvelope.ToolMaterial(id, draft.path(), draft.side(), draft.sha(), draft.startLine(),
                    draft.endLine(), draft.contentHash(), draft.completeness(), draft.content());
        });
        SearchCodeTool search = new SearchCodeTool(repository, materials, mirror, grep, new ToolProperties(), mapper);
        service = new ReadOnlyToolService(mapper, repository, mock(ReadCodeTool.class), search, mock(ReadDiffTool.class));
    }

    @ParameterizedTest
    @CsvSource({ "32768,false", "4096,false", "32768,true", "4096,true" })
    void escapedUnicodeResultsPageWithoutLosingHitsOrExceedingTheWireLimit(int maxBytes, boolean escapeUnicode) throws Exception {
        mapper.getFactory().configure(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), escapeUnicode);
        String line = "needle \"中文\\\t".repeat(24);
        List<GitGrepRunner.Hit> expected = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            expected.add(new GitGrepRunner.Hit("src/" + "中文目录".repeat(12) + "/File" + index + ".java", 2, line));
        }
        stubResults(expected, "前文\n" + line + "\n后文\n");
        ToolLimitProperties limits = new ToolLimitProperties();
        limits.setEnvelopeMaxBytes(maxBytes);
        limits.setMaterialMaxBytes(Math.min(limits.getMaterialMaxBytes(), maxBytes - 2048));
        Map<String, String> versions = new LinkedHashMap<>();
        for (int version = 1; version <= 10; version++) versions.put("V" + version, SHA);
        AgentToolScope scope = new AgentToolScope("p1", versions, Map.of(), limits);
        List<String> actual = new ArrayList<>();
        var cursors = new HashSet<String>();
        String cursor = null;
        int pages = 0;
        do {
            ToolEnvelope envelope = search(scope, cursor);
            assertEquals(ToolEnvelope.OK, envelope.status(), envelope.diagnostic());
            assertTrue(ToolEnvelopes.size(mapper, envelope) <= maxBytes);
            assertFalse(hits(envelope).isEmpty());
            for (Map<String, Object> hit : hits(envelope)) actual.add((String) hit.get("path"));
            cursor = envelope.nextCursor();
            if (cursor != null) {
                assertEquals(ToolEnvelope.PARTIAL, envelope.completeness());
                assertTrue(cursors.add(cursor), "cursor must advance");
            } else {
                boolean incomplete = envelope.materials().stream()
                        .anyMatch(material -> !ToolEnvelope.COMPLETE.equals(material.completeness()));
                assertEquals(incomplete ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE, envelope.completeness());
            }
            assertTrue(++pages <= expected.size());
        } while (cursor != null);
        assertTrue(pages > 1);
        assertEquals(expected.stream().map(GitGrepRunner.Hit::path).toList(), actual);
        verify(materials, times(expected.size())).save(eq("p1"), any());
    }

    @Test
    void oneEscapingHeavyHitReturnsAnExplicitlyShortenedMaterial() throws Exception {
        String line = "needle " + "\"\\\t\u0001".repeat(5000);
        stubResults(List.of(new GitGrepRunner.Hit("src/Huge.java", 2, line)), "before\n" + line + "\nafter\n");

        ToolEnvelope envelope = search(AgentToolScope.single("p1", SHA), null);

        assertEquals(ToolEnvelope.OK, envelope.status(), envelope.diagnostic());
        assertTrue(ToolEnvelopes.size(mapper, envelope) <= ToolLimits.ENVELOPE_MAX_BYTES);
        assertEquals(1, hits(envelope).size());
        assertEquals(1, envelope.materials().size());
        assertEquals(ToolEnvelope.PARTIAL, envelope.completeness());
        assertNull(envelope.nextCursor());
        var material = envelope.materials().get(0);
        assertEquals(2, material.startLine());
        assertEquals(2, material.endLine());
        assertEquals(ToolEnvelope.PARTIAL, material.completeness());
        assertTrue(material.content().contains("已截短"));
        assertEquals(ToolTexts.sha256(material.content()), material.contentHash());
        assertEquals(material.materialId(), hits(envelope).get(0).get("materialId"));
        assertTrue(envelope.diagnostic().contains("read_code"));
        verify(materials).save(eq("p1"), any());
    }

    @Test
    void truncatedGrepRemainsPartialEvenWhenTheRetainedHitsAreExhausted() throws Exception {
        var retained = List.of(new GitGrepRunner.Hit("src/A.java", 2, "needle"));
        stubResults(retained, "before\nneedle\nafter\n");
        when(grep.search(any(), eq(SHA), eq("needle"), any(), any(), anyInt()))
                .thenReturn(new GitGrepRunner.Result(retained, true, false, 0, null));

        ToolEnvelope envelope = search(AgentToolScope.single("p1", SHA), null);

        assertEquals(ToolEnvelope.OK, envelope.status());
        assertEquals(ToolEnvelope.PARTIAL, envelope.completeness());
        assertNull(envelope.nextCursor());
        assertEquals(1, hits(envelope).size());
        assertTrue(envelope.diagnostic().contains("grep 输出达到上限"));
    }

    private void stubResults(List<GitGrepRunner.Hit> hits, String content) {
        when(grep.search(any(), eq(SHA), eq("needle"), any(), any(), anyInt()))
                .thenReturn(new GitGrepRunner.Result(hits, false, false, 0, null));
        when(repository.readFile(eq("p1"), eq(SHA), anyString())).thenAnswer(i ->
                new RepositorySnapshots.FileSnapshot(SHA, i.getArgument(2), content, ToolTexts.sha256(content),
                        ToolTexts.utf8Bytes(content), ToolTexts.splitLines(content).length));
    }

    private ToolEnvelope search(AgentToolScope scope, String cursor) throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("version", "V1");
        arguments.put("query", "needle");
        arguments.put("limit", 50);
        if (cursor != null) arguments.put("cursor", cursor);
        return service.execute("provider-call", AgentToolNames.SEARCH_CODE, mapper.writeValueAsString(arguments), scope);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hits(ToolEnvelope envelope) {
        return (List<Map<String, Object>>) ((Map<?, ?>) envelope.data()).get("hits");
    }
}
