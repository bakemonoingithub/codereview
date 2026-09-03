package com.codereview.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * GitHub 实现；token 可空（公开仓库匿名访问，但有频率限制）
 */
@Component
public class GitHubClient implements GitHostClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String RAW_BASE = "https://raw.githubusercontent.com";
    private final RestClient restClient = restClient();
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
        // 优先走 Contents API（与树接口同域 api.github.com），
        // 避免 raw.githubusercontent.com 直连被墙/超时（浏览器/系统代理可达但 JVM 直连不可达）。
        String contentsUrl = API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + encodePathSegments(path)
                + "?ref=" + UriUtils.encodeQueryParam(branch, StandardCharsets.UTF_8);
        JsonNode root = getJson(contentsUrl, token);
        String encoding = root.path("encoding").asText();
        String content = root.path("content").asText();
        if ("base64".equalsIgnoreCase(encoding) && !content.isBlank()) {
            try {
                return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 解码失败时回退 raw
            }
        }
        // 回退：raw.githubusercontent.com（>1MB 时 Contents API 不返回 content）
        String rawUrl = RAW_BASE + "/" + owner + "/" + repo + "/" + branch + "/" + encodePathSegments(path);
        return get(rawUrl, token);
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

    private static RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 按路径段编码（保留斜杠），避免空格/中文/特殊字符导致 Contents API 404。 */
    private static String encodePathSegments(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
