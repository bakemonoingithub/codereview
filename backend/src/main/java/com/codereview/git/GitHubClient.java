package com.codereview.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 实现；token 可空（公开仓库匿名访问，但有频率限制）
 */
@Component
public class GitHubClient implements GitHostClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String RAW_BASE = "https://raw.githubusercontent.com";
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<GitTreeEntry> tree(String token, String owner, String repo, String branch) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/git/trees/" + branch + "?recursive=1";
        JsonNode root = getJson(url, token);
        List<GitTreeEntry> entries = new ArrayList<>();
        for (JsonNode node : root.path("tree")) {
            String type = node.path("type").asText();
            if ("blob".equals(type) || "tree".equals(type)) {
                entries.add(new GitTreeEntry(node.path("path").asText(), type));
            }
        }
        return entries;
    }

    @Override
    public String rawFile(String token, String owner, String repo, String branch, String path) {
        String url = RAW_BASE + "/" + owner + "/" + repo + "/" + branch + "/" + path;
        return get(url, token);
    }

    @Override
    public List<String> branches(String token, String owner, String repo) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/branches";
        JsonNode root = getJson(url, token);
        List<String> names = new ArrayList<>();
        for (JsonNode node : root) {
            names.add(node.path("name").asText());
        }
        return names;
    }

    private JsonNode getJson(String url, String token) {
        try {
            return mapper.readTree(get(url, token));
        } catch (Exception e) {
            throw new IllegalStateException("GitHub 响应解析失败: " + e.getMessage(), e);
        }
    }

    private String get(String url, String token) {
        if (token != null && !token.isBlank()) {
            return restClient.get().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().body(String.class);
        }
        return restClient.get().uri(url).retrieve().body(String.class);
    }
}
