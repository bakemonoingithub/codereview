package com.codereview.analyzer;

import com.codereview.material.ClassInfo;
import com.codereview.material.DepEdge;
import com.codereview.material.Material;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouplingAnalyzerTest {

    private static Material material() {
        ClassInfo a = new ClassInfo("A.java", "p", "A", "class", List.of(), List.of(), List.of(), List.of(), List.of());
        ClassInfo b = new ClassInfo("B.java", "p", "B", "class", List.of(), List.of(), List.of(), List.of(), List.of());
        ClassInfo c = new ClassInfo("C.java", "p", "C", "class", List.of(), List.of(), List.of(), List.of(), List.of());
        List<DepEdge> edges = List.of(
                new DepEdge("p.A", "p.B"),
                new DepEdge("p.B", "p.C"),
                new DepEdge("p.C", "p.A"));
        return new Material("p", List.of(a, b, c), edges, "");
    }

    @Test
    void buildDeterministicWithDefaultThreshold() {
        ObjectMapper mapper = new ObjectMapper();
        var root = CouplingAnalyzer.buildDeterministic(material(), 10, mapper);
        assertEquals(3, root.path("nodes").size());
        assertEquals(3, root.path("edges").size());
        assertEquals(1, root.path("cycles").size());
        assertEquals(0, root.path("highCoupling").size()); // 扇出各 1，不超阈值 10
    }

    @Test
    void buildDeterministicWithLowThreshold() {
        ObjectMapper mapper = new ObjectMapper();
        var root = CouplingAnalyzer.buildDeterministic(material(), 0, mapper);
        assertEquals(3, root.path("highCoupling").size()); // 阈值 0 → 全部高耦合
    }

    @Test
    void addsModuleLevelFieldsWithoutDroppingClassLevelOnes() {
        var mapper = new ObjectMapper();
        var root = CouplingAnalyzer.buildDeterministic(material(), 10, mapper);

        // 类级字段一个都不能少（前端图与旧记录都在读）
        assertEquals(3, root.path("nodes").size());
        assertEquals(3, root.path("edges").size());
        assertEquals(1, root.path("cycles").size());
        assertEquals(0, root.path("highCoupling").size(), "阈值 10 时扇出各 1，无高耦合类");
        assertTrue(root.path("nodes").get(0).has("fanOut"));

        // 模块级字段：单一包 → 一个模块 + "不适用"的确定性结论
        assertTrue(root.has("moduleSummary"));
        assertEquals("模块 1 个（(根包)，3 个类）；模块级结论不适用（项目只有一个包，无跨模块依赖与循环）",
                root.path("moduleSummary").asText());
        assertEquals(1, root.path("modules").size());
        assertEquals("(根包)", root.path("modules").get(0).path("name").asText());
        assertEquals(3, root.path("modules").get(0).path("classCount").asInt());
        assertTrue(root.path("moduleEdges").isArray());
        assertEquals(0, root.path("moduleEdges").size(), "同包依赖不进模块图");
        assertEquals(0, root.path("moduleCycles").size());
        assertEquals(0, root.path("moduleMutualPairs").size());
    }

    @Test
    void summaryPutsModulesBeforeClassLevel() {
        var root = CouplingAnalyzer.buildDeterministic(material(), 10, new ObjectMapper());

        String summary = root.path("summary").asText();
        assertEquals(root.path("moduleSummary").asText()
                + "；类级：依赖图 3 节点 / 3 边；高耦合 0 个；循环依赖 1 组", summary);
        assertTrue(summary.indexOf("模块 1 个") < summary.indexOf("类级："), "模块结论必须排在类级之前");
    }

    @Test
    void moduleLevelFieldsSurviveAcrossPackages() {
        ClassInfo order = new ClassInfo("Order.java", "com.shop.order", "Order", "class",
                List.of(), List.of(), List.of(), List.of(), List.of());
        ClassInfo inv = new ClassInfo("Inventory.java", "com.shop.inventory", "Inventory", "class",
                List.of(), List.of(), List.of(), List.of(), List.of());
        Material multi = new Material("com.shop", List.of(order, inv),
                List.of(new DepEdge("com.shop.order.Order", "com.shop.inventory.Inventory")), "");

        var root = CouplingAnalyzer.buildDeterministic(multi, 10, new ObjectMapper());

        assertEquals(2, root.path("modules").size());
        assertEquals("order", root.path("modules").get(0).path("name").asText());
        assertEquals(1, root.path("moduleEdges").size());
        assertEquals(1, root.path("moduleEdges").get(0).path("weight").asInt());
        assertTrue(root.path("moduleSummary").asText().contains("跨模块依赖 1 对、共 1 处类级依赖"));
    }
}
