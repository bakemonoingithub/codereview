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
 * GitHub 实现；token 可空（项目级 credential 为空时回退 github.token 兜底认证，
 * 两者都为空才匿名访问公开仓库，但仅 60 次/小时/IP）
 */
@Component
public class GitHubClient implements GitHostClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String RAW_BASE = "https://raw.githubusercontent.com";
    private final GitHubProperties properties;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubClient(GitHubProperties properties) {
        this.properties = properties;
        this.restClient = restClient();
    }

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
    public String headCommitSha(String token, String owner, String repo, String branch) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/branches/"
                + UriUtils.encodePathSegment(branch, StandardCharsets.UTF_8);
        JsonNode root = getJson(url, token);
        return root.path("commit").path("sha").asText();
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
        // 项目级 token 优先；为空时回退到全局兜底 token（github.token），
        // 避免匿名请求触发 60 次/小时/IP 的限流。
        String effective = resolveToken(token);
        if (effective == null || effective.isBlank()) {
            return restClient.get().uri(url).retrieve().body(String.class);
        }
        return restClient.get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + effective)
                .retrieve().body(String.class);
    }

    /** 取实际生效的令牌：项目级 credential 优先，其次全局兜底 token。 */
    private String resolveToken(String token) {
        if (token != null && !token.isBlank()) {
            return token;
        }
        String fallback = properties.getToken();
        return (fallback == null || fallback.isBlank()) ? null : fallback;
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
