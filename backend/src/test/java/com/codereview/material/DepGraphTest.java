package com.codereview.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DepGraphTest {

    private static Material material() {
        ClassInfo a = new ClassInfo("A.java", "p", "A", "class", List.of(), List.of(), List.of(), List.of(), List.of());
        ClassInfo b = new ClassInfo("B.java", "p", "B", "class", List.of(), List.of(), List.of(), List.of(), List.of());
        ClassInfo c = new ClassInfo("C.java", "p", "C", "class", List.of(), List.of(), List.of(), List.of(), List.of());
        List<ClassInfo> classes = List.of(a, b, c);
        List<DepEdge> edges = List.of(
                new DepEdge("p.A", "p.B"),
                new DepEdge("p.B", "p.C"),
                new DepEdge("p.C", "p.A"),
                new DepEdge("p.A", "p.C"));
        return new Material("p", classes, edges, "");
    }

    @Test
    void fanInOut() {
        DepGraph g = new DepGraph(material());
        assertEquals(2, g.fanOut("p.A")); // -> B, C
        assertEquals(1, g.fanIn("p.A"));  // C -> A
        assertEquals(1, g.fanOut("p.B")); // -> C
        assertEquals(1, g.fanIn("p.B"));  // A -> B
    }

    @Test
    void detectsCycle() {
        DepGraph g = new DepGraph(material());
        List<List<String>> cycles = g.findCycles();
        assertEquals(1, cycles.size());
        assertEquals(3, cycles.get(0).size());
    }

    @Test
    void highCoupling() {
        DepGraph g = new DepGraph(material());
        List<String> high = g.highCoupling(1); // fanOut > 1
        assertEquals(List.of("p.A"), high);
    }
}
