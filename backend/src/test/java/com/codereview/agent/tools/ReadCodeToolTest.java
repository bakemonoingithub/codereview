package com.codereview.agent.tools;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadCodeToolTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String PATH = "src/A.java";

    @Mock
    private RepositoryReadService repository;
    @Mock
    private ToolMaterialStore materialStore;

    private ReadCodeTool tool;
    private final AgentToolScope scope = AgentToolScope.single("p1", SHA);

    @BeforeEach
    void setUp() {
        tool = new ReadCodeTool(repository, materialStore);
        when(materialStore.save(anyString(), any())).thenAnswer(invocation -> {
            ToolEnvelope.ToolMaterial draft = invocation.getArgument(1);
            return new ToolEnvelope.ToolMaterial("m1", draft.path(), draft.side(), draft.sha(),
                    draft.startLine(), draft.endLine(), draft.contentHash(), draft.completeness(),
                    draft.content());
        });
    }

    @Test
    void frozenCustomReadLimitsChangeActualReturnedRangeAndContinuation() {
        stubFile(20);
        ToolLimitProperties limits = new ToolLimitProperties();
        limits.setReadDefaultLines(3); limits.setReadMaxLines(4);
        var custom = new AgentToolScope("p1", Map.of("V1", SHA), Map.of(), limits);
        var first = tool.execute("c1", invocation(1, null, null), custom);
        assertEquals(3, first.materials().get(0).endLine());
        assertEquals(4, ((Map<?, ?>) first.data()).get("nextStartLine"));
        var capped = tool.execute("c2", invocation(1, 4, 20), custom);
        assertEquals(7, capped.materials().get(0).endLine());
        assertEquals(8, ((Map<?, ?>) capped.data()).get("nextStartLine"));
        assertEquals(ToolEnvelope.PARTIAL, capped.completeness());
    }

    @Test
    void defaultsToTwoHundredLinesAndPointsAtTheNextLine() {
        stubFile(250);

        ToolEnvelope envelope = tool.execute("call-1", invocation(1, null, null), scope);

        assertEquals(ToolEnvelope.OK, envelope.status());
        // 默认请求的 200 行已完整返回；文件还有内容时由 nextStartLine 指出继续位置。
        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        Map<?, ?> data = (Map<?, ?>) envelope.data();
        assertEquals(1, data.get("startLine"));
        assertEquals(200, data.get("endLine"));
        assertEquals(250, data.get("totalLines"));
        assertEquals(201, data.get("nextStartLine"));
        assertEquals(1, envelope.materials().size());
        assertEquals(200, envelope.materials().get(0).endLine());
    }

    @Test
    void completesWhenTheWholeFileIsReturned() {
        stubFile(50);

        ToolEnvelope envelope = tool.execute("call-1", invocation(1, null, null), scope);

        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        Map<?, ?> data = (Map<?, ?>) envelope.data();
        assertEquals(50, data.get("endLine"));
        assertNull(data.get("nextStartLine"));
    }

    @Test
    void capsARequestedRangeAtFourHundredLinesAndSaysSo() {
        stubFile(900);

        ToolEnvelope envelope = tool.execute("call-1", invocation(1, 1, 900), scope);

        assertEquals(ToolEnvelope.PARTIAL, envelope.completeness());
        Map<?, ?> data = (Map<?, ?>) envelope.data();
        assertEquals(400, data.get("endLine"));
        assertEquals(401, data.get("nextStartLine"));
        assertTrue(envelope.diagnostic().contains("400"));
    }

    @Test
    void capsMaterialBytesEvenWhenTheLineLimitAllowsMore() {
        stubFile(300, 200);

        ToolEnvelope envelope = tool.execute("call-1", invocation(1, 1, 300), scope);

        ToolEnvelope.ToolMaterial material = envelope.materials().get(0);
        int bytes = material.content().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= ToolLimits.MATERIAL_MAX_BYTES, "material bytes=" + bytes);
        assertTrue(material.endLine() < 300);
        assertEquals(material.endLine() + 1, ((Map<?, ?>) envelope.data()).get("nextStartLine"));
        assertEquals(ToolEnvelope.PARTIAL, envelope.completeness());
    }

    @Test
    void beyondTheEndReturnsAnExplicitEmptyResultWithoutMaterial() {
        stubFile(10);

        ToolEnvelope envelope = tool.execute("call-1", invocation(1, 50, 60), scope);

        assertEquals(ToolEnvelope.OK, envelope.status());
        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        assertTrue(envelope.materials().isEmpty());
        assertTrue(envelope.diagnostic().contains("超出文件末尾"));
        assertNull(((Map<?, ?>) envelope.data()).get("nextStartLine"));
    }

    @Test
    void emptyFileIsACompleteEmptyRead() {
        stubFile(0);

        ToolEnvelope envelope = tool.execute("call-1", invocation(1, null, null), scope);

        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        assertTrue(envelope.materials().isEmpty());
        assertEquals(0, ((Map<?, ?>) envelope.data()).get("totalLines"));
    }

    @Test
    void persistsExactlyTheReturnedRange() {
        stubFile(30);

        tool.execute("call-1", invocation(1, 5, 9), scope);

        ArgumentCaptor<ToolEnvelope.ToolMaterial> saved =
                ArgumentCaptor.forClass(ToolEnvelope.ToolMaterial.class);
        verify(materialStore).save(anyString(), saved.capture());
        assertEquals(5, saved.getValue().startLine());
        assertEquals(9, saved.getValue().endLine());
        assertEquals("FILE", saved.getValue().side());
        assertEquals(SHA, saved.getValue().sha());
    }

    private ToolInvocation invocation(int limit, Integer start, Integer end) {
        return new ToolInvocation(AgentToolNames.READ_CODE, "V1", SHA, null, PATH, null, limit,
                null, null, null, java.util.List.of(), start, end);
    }

    private void stubFile(int lines) {
        stubFile(lines, 10);
    }

    private void stubFile(int lines, int charsPerLine) {
        StringBuilder content = new StringBuilder();
        for (int line = 0; line < lines; line++) {
            content.append("x".repeat(charsPerLine)).append('\n');
        }
        String text = content.toString();
        int byteSize = text.getBytes(StandardCharsets.UTF_8).length;
        when(repository.readFile("p1", SHA, PATH)).thenReturn(new RepositorySnapshots.FileSnapshot(
                SHA, PATH, text, ToolTexts.sha256(text), byteSize, lines));
    }
}
