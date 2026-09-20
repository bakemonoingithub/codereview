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
    void parseKeepsSchemeHostAndSubPathInBaseUrl() {
        GitRepoRef r = GitRepoRef.parse("http://192.104.224.172/gitea/team/proj");

        assertEquals("http://192.104.224.172/gitea", r.baseUrl());
        assertEquals("http://192.104.224.172/gitea/api/v1", r.apiBase(),
                "Gitea 的 API 根 = 站点根（含子路径）+ /api/v1");
        assertEquals("192.104.224.172", r.host());
        assertEquals("team", r.owner());
        assertEquals("proj", r.repo());
    }

    @Test
    void parseRootDeploymentHasNoSubPath() {
        GitRepoRef r = GitRepoRef.parse("http://localhost:3000/gitea_admin/codereview");

        assertEquals("http://localhost:3000", r.baseUrl());
        assertEquals("http://localhost:3000/api/v1", r.apiBase());
        assertEquals("localhost:3000", r.host(), "host 含端口：白名单要按 host:port 匹配");
    }

    @Test
    void sshFormHasNoBaseUrlSoApiBaseMustBeConfigured() {
        GitRepoRef r = GitRepoRef.parse("git@gitea.example.com:team/proj.git");

        assertEquals(null, r.baseUrl());
        assertEquals(null, r.apiBase());
    }

    @Test
    void parseInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> GitRepoRef.parse("https://github.com/onlyowner"));
    }
}
