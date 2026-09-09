package com.codereview.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkerTest {

    @Test
    void smallFileIsSingleUnit() {
        String code = "public class A {\n}\n";
        List<ReviewUnit> units = Chunker.chunk("src/A.java", code, 100);
        assertEquals(1, units.size());
        ReviewUnit u = units.get(0);
        assertEquals("file", u.kind());
        assertEquals("A.java", u.name());
        assertEquals(1, u.startLine());
        assertEquals(code, u.code());
    }

    @Test
    void overThresholdSplitsByMethod() {
        String code = """
                package p;
                public class Foo {
                    public void a() {
                        int x = 1;
                        System.out.println(x);
                    }
                    public void b() {
                        int y = 2;
                        System.out.println(y);
                    }
                }
                """;
        List<ReviewUnit> units = Chunker.chunk("src/Foo.java", code, 100);
        assertEquals(2, units.size());
        assertEquals("method", units.get(0).kind());
        assertEquals("a", units.get(0).name());
        assertEquals(3, units.get(0).startLine());
        assertEquals(6, units.get(0).endLine());
        assertTrue(units.get(0).code().contains("void a()"));
        assertEquals("b", units.get(1).name());
    }

    @Test
    void parseFailureFallsBackToLines() {
        String code = "not java code\n".repeat(50);
        List<ReviewUnit> units = Chunker.chunk("x.txt", code, 200);
        assertTrue(units.size() > 1);
        assertEquals("file", units.get(0).kind());
    }

    @Test
    void blankReturnsEmpty() {
        assertEquals(0, Chunker.chunk("x.java", "  \n", 100).size());
    }
}
