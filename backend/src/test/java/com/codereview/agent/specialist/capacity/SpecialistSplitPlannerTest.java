package com.codereview.agent.specialist.capacity;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.invocation.ModelCapacityService;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.specialist.SpecialistProperties;
import com.codereview.agent.specialist.material.ContentHash;
import com.codereview.agent.specialist.material.JavaSourceStructure;
import com.codereview.agent.specialist.material.JavaStructureParser;
import com.codereview.agent.specialist.material.MaterialStore;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpecialistSplitPlannerTest {

    private static final String PROJECT = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    private MaterialStore store;
    private SpecialistSplitPlanner planner;
    private final JavaStructureParser parser = new JavaStructureParser();

    @BeforeEach
    void setUp() {
        store = mock(MaterialStore.class);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ModelInvocationProperties modelProperties = new ModelInvocationProperties();
        modelProperties.setOutputReserveTokens(64);
        ModelCapacityService capacity = new ModelCapacityService(modelProperties);
        SpecialistProperties properties = new SpecialistProperties();
        properties.setMaxBlockLines(20);
        planner = new SpecialistSplitPlanner(new PromptAssemblyService(), capacity,
                new SpecialistPromptBuilder(), store, properties);
    }

    @Test
    void fittingFullRequestKeepsAuxiliaryMaterials() {
        SpecialistMaterials.PreparedTarget base = target("app/Small.java",
                "package app;\npublic class Small {\n    void run() {\n    }\n}\n");
        SpecialistMaterials.Material auxiliary = new SpecialistMaterials.Material(
                "01J9Z6Q3W4X5Y6Z7A8B9C0D1E5", PROJECT, SHA, "app/Base.java",
                SpecialistMaterials.Side.FILE, 1, 2, "class Base {\n}\n", "aux-hash",
                "DECLARATION_SUMMARY", null, SpecialistMaterials.Role.AUXILIARY,
                SpecialistMaterials.Status.RESOLVED, "app.Base", null);
        SpecialistMaterials.PreparedTarget withAux = new SpecialistMaterials.PreparedTarget(
                base.projectId(), base.specialistType(), base.sourceKind(), base.branch(),
                base.path(), null, base.headSha(), null, null, false, false,
                base.targets(), List.of(auxiliary), base.gaps(), null, null);

        List<SpecialistUnitPlan> units = planner.plan(withAux, null, null, null, model(100_000), false);

        assertEquals(1, units.size());
        assertEquals(1, units.get(0).target().auxiliary().size());
    }

    @Test
    void refusesToSplitWhenTheSwitchIsOff() {
        SpecialistMaterials.PreparedTarget target = target("app/Big.java", bigJava(100));

        ApiException failure = assertThrows(ApiException.class, () -> planner.plan(target, null,
                null, null, model(7000), false));

        assertEquals(ErrorCode.CONTEXT_LIMIT, failure.errorCode());
        assertTrue(failure.getMessage().contains("未开启智能拆分"));
    }

    @Test
    void splitsByMembersAndKeepsOriginalLineCoverage() {
        String content = bigJava(100);
        SpecialistMaterials.PreparedTarget target = target("app/Big.java", content);

        List<SpecialistUnitPlan> units = planner.plan(target, null, null, null, model(7000), true);

        assertTrue(units.size() > 1, "超大文件应拆成多个单元");
        boolean[] covered = new boolean[JavaStructureParser.countLines(content) + 1];
        for (SpecialistUnitPlan unit : units) {
            assertFalse(unit.target().targets().isEmpty());
            for (JavaSourceStructure.LineRange range : unit.ranges()) {
                for (int line = range.startLine(); line <= range.endLine(); line++) {
                    covered[line] = true;
                }
            }
        }
        for (int line = 1; line < covered.length; line++) {
            assertTrue(covered[line], "缺少原始行覆盖：" + line);
        }
    }

    @Test
    void prunesLowPriorityAuxiliaryMaterialsBeforeSplitting() {
        SpecialistMaterials.PreparedTarget target = target("app/Small.java",
                "package app;\npublic class Small {\n    void run() {\n    }\n}\n");
        SpecialistMaterials.Material auxiliary = new SpecialistMaterials.Material(
                "01J9Z6Q3W4X5Y6Z7A8B9C0D1E4", PROJECT, SHA, "app/Base.java",
                SpecialistMaterials.Side.FILE, 1, 2, "class Base {\n" + "x".repeat(20_000) + "\n}",
                "aux-hash", "DECLARATION_SUMMARY", null, SpecialistMaterials.Role.AUXILIARY,
                SpecialistMaterials.Status.RESOLVED, "app.Base", null);
        SpecialistMaterials.PreparedTarget withAux = new SpecialistMaterials.PreparedTarget(
                target.projectId(), target.specialistType(), target.sourceKind(), target.branch(),
                target.path(), null, target.headSha(), null, null, false, false,
                target.targets(), List.of(auxiliary), target.gaps(), null, null);

        List<SpecialistUnitPlan> units = planner.plan(withAux, null, null, null, model(7000), true);

        assertEquals(1, units.size());
        assertTrue(units.get(0).target().auxiliary().isEmpty());
        assertTrue(units.get(0).notes().stream().anyMatch(note -> note.contains("移除")));
    }

    @Test
    void singleOversizedLineIsUnsplittable() {
        String content = "x".repeat(30_000);
        SpecialistMaterials.PreparedTarget target = target("src/data.txt", content);

        ApiException failure = assertThrows(ApiException.class, () -> planner.plan(target, null,
                null, null, model(7000), true));

        assertEquals(ErrorCode.UNSPLITTABLE, failure.errorCode());
    }

    private SpecialistMaterials.PreparedTarget target(String path, String content) {
        JavaSourceStructure structure = path.endsWith(".java") ? parser.parse(path, content) : null;
        SpecialistMaterials.Material material = new SpecialistMaterials.Material(
                "01J9Z6Q3W4X5Y6Z7A8B9C0D1E3", PROJECT, SHA, path,
                SpecialistMaterials.Side.FILE, 1, JavaStructureParser.countLines(content), content,
                ContentHash.sha256(content), "COMPLETE", null, SpecialistMaterials.Role.TARGET,
                SpecialistMaterials.Status.RESOLVED, null, structure);
        return new SpecialistMaterials.PreparedTarget(PROJECT, "ISSUE", "FILES", "main", path,
                null, SHA, null, null, false, false, List.of(material), List.of(), List.of(),
                null, null);
    }

    private static ModelSnapshot model(int window) {
        return new ModelSnapshot("m1", "默认", "https://gateway.local/v1", "model", "secret",
                window, 64, null, "OPENAI_CHAT", 1);
    }

    private static String bigJava(int methods) {
        StringBuilder source = new StringBuilder("package app;\n\npublic class Big {\n");
        for (int i = 0; i < methods; i++) {
            source.append("    public int method").append(i)
                    .append("(int value) { return value + ").append(i).append("; }\n");
        }
        source.append("}\n");
        return source.toString();
    }
}
