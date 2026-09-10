package com.codereview.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextDiffTest {

    @Test
    void identical() {
        List<TextDiff.DiffLine> d = TextDiff.diff("a\nb", "a\nb");
        assertEquals(2, d.size());
        assertEquals("same", d.get(0).type());
        assertEquals("same", d.get(1).type());
    }

    @Test
    void addLine() {
        List<TextDiff.DiffLine> d = TextDiff.diff("a\nb", "a\nb\nc");
        assertEquals(3, d.size());
        assertEquals("add", d.get(2).type());
        assertEquals("c", d.get(2).text());
        assertEquals(3, d.get(2).newLine());
    }

    @Test
    void removeLine() {
        List<TextDiff.DiffLine> d = TextDiff.diff("a\nb\nc", "a\nb");
        assertEquals(3, d.size());
        assertEquals("remove", d.get(2).type());
        assertEquals("c", d.get(2).text());
        assertEquals(3, d.get(2).oldLine());
    }

    @Test
    void replaceLine() {
        List<TextDiff.DiffLine> d = TextDiff.diff("a\nb", "a\nc");
        assertEquals(3, d.size());
        assertEquals("same", d.get(0).type());
        assertEquals("remove", d.get(1).type());
        assertEquals("add", d.get(2).type());
    }
}
