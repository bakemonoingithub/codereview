package com.codereview.agent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGlobsTest {

    @Test
    void singleStarStaysInsideOneSegment() {
        assertTrue(ToolGlobs.matches("*.java", "A.java"));
        assertFalse(ToolGlobs.matches("*.java", "src/A.java"));
        assertTrue(ToolGlobs.matches("src/*.java", "src/A.java"));
        assertFalse(ToolGlobs.matches("src/*.java", "src/x/A.java"));
    }

    @Test
    void doubleStarMatchesZeroOrMoreSegments() {
        assertTrue(ToolGlobs.matches("src/**/*.java", "src/A.java"));
        assertTrue(ToolGlobs.matches("src/**/*.java", "src/a/b/A.java"));
        assertFalse(ToolGlobs.matches("src/**/*.java", "other/A.java"));
        assertTrue(ToolGlobs.matches("**/*.md", "README.md"));
        assertTrue(ToolGlobs.matches("**/*.md", "docs/a/README.md"));
    }

    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        assertTrue(ToolGlobs.matches("A?.java", "A1.java"));
        assertFalse(ToolGlobs.matches("A?.java", "A12.java"));
        assertFalse(ToolGlobs.matches("A?.java", "A/.java"));
    }

    @Test
    void matchingIsCaseSensitiveLikeTheRepository() {
        assertTrue(ToolGlobs.matches("*.JAVA", "A.JAVA"));
        assertFalse(ToolGlobs.matches("*.JAVA", "A.java"));
    }

    @Test
    void regexMetacharactersInPathsAreQuoted() {
        assertTrue(ToolGlobs.matches("a+b(1).txt", "a+b(1).txt"));
        assertFalse(ToolGlobs.matches("a+b(1).txt", "aab1.txt"));
    }
}
