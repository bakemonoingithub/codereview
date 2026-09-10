package com.codereview.analyzer;

import com.codereview.material.ClassInfo;
import com.codereview.material.DepEdge;
import com.codereview.material.Material;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
