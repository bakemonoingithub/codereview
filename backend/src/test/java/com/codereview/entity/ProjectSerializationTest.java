package com.codereview.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目凭据只写不读（A 批 E4）。
 *
 * <p>回归背景：{@code Project.credential} 原先没加 {@code @JsonProperty(WRITE_ONLY)}，
 * 而 {@code GET /api/projects} 直接返回实体 ⇒ **每次项目列表都把仓库 token/密码明文
 * 发给浏览器**。对照 {@link ModelConfig} 的 token 一直是正确挡住的。
 */
class ProjectSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void credentialIsNeverSerialized() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("demo");
        project.setGiteaUrl("http://gitea.local/team/repo");
        project.setCredential("ghp_super_secret_token");
        project.setCredentialType(1);

        String json = mapper.writeValueAsString(project);

        assertFalse(json.contains("ghp_super_secret_token"), "凭据明文不能出现在响应里: " + json);
        assertFalse(json.contains("\"credential\""), "credential 字段本身也不应出现: " + json);
        // 非敏感字段照常回传，避免"一刀切隐藏"把界面打坏
        assertTrue(json.contains("giteaUrl"), json);
        assertTrue(json.contains("credentialType"), "credentialType 只是类型标记，不敏感: " + json);
    }

    @Test
    void credentialIsStillAcceptedOnInput() throws Exception {
        // 只写不读：创建项目时仍要能从请求体读入令牌，否则私有仓库没法接
        Project project = mapper.readValue("{\"name\":\"n\",\"credential\":\"tok-123\"}", Project.class);

        assertEquals("tok-123", project.getCredential());
    }
}
