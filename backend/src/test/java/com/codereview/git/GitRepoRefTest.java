package com.codereview.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitRepoRefTest {

    @Test
    void parseHttpsWithGitSuffix() {
        GitRepoRef r = GitRepoRef.parse("https://github.com/bakemonoingithub/AnisongManage.git");
        assertEquals("github.com", r.host());
        assertEquals("bakemonoingithub", r.owner());
        assertEquals("AnisongManage", r.repo());
    }

    @Test
    void parseHttpsWithoutGitSuffix() {
        GitRepoRef r = GitRepoRef.parse("https://github.com/bakemonoingithub/AnisongManage");
        assertEquals("github.com", r.host());
        assertEquals("bakemonoingithub", r.owner());
        assertEquals("AnisongManage", r.repo());
    }

    @Test
    void parseSsh() {
        GitRepoRef r = GitRepoRef.parse("git@gitea.example.com:team/proj.git");
        assertEquals("gitea.example.com", r.host());
        assertEquals("team", r.owner());
        assertEquals("proj", r.repo());
    }

    @Test
    void parseTrailingSlash() {
        GitRepoRef r = GitRepoRef.parse("https://gitea.example.com/team/proj/");
        assertEquals("proj", r.repo());
        assertEquals("team", r.owner());
    }

    @Test
    void parseInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> GitRepoRef.parse("https://github.com/onlyowner"));
    }
}
