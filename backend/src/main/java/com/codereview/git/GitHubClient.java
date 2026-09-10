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
 * 两者都为空才匿名访问公开仓库，但仅 60 次/小时/IP）。
 * credentialType=2 时按账号密码走 Basic Auth（credential 形如 user:pass）。
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
    public List<GitTreeEntry> tree(String token, Integer credentialType, String owner, String repo, String branch) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/git/trees/" + branch + "?recursive=1";
        JsonNode root = getJson(url, token, credentialType);
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
    public String rawFile(String token, Integer credentialType, String owner, String repo, String branch, String path) {
        // 优先走 Contents API（与树接口同域 api.github.com），避免 raw.githubusercontent.com 直连被墙/超时。
        String contentsUrl = API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + encodePathSegments(path)
                + "?ref=" + UriUtils.encodeQueryParam(branch, StandardCharsets.UTF_8);
        JsonNode root = getJson(contentsUrl, token, credentialType);
        String encoding = root.path("encoding").asText();
        String content = root.path("content").asText();
        if ("base64".equalsIgnoreCase(encoding) && !content.isBlank()) {
            try {
                return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 解码失败时回退 raw
            }
        }
        String rawUrl = RAW_BASE + "/" + owner + "/" + repo + "/" + branch + "/" + encodePathSegments(path);
        return get(rawUrl, token, credentialType);
    }

    @Override
    public String headCommitSha(String token, Integer credentialType, String owner, String repo, String branch) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/branches/"
                + UriUtils.encodePathSegment(branch, StandardCharsets.UTF_8);
        JsonNode root = getJson(url, token, credentialType);
        return root.path("commit").path("sha").asText();
    }

    @Override
    public List<String> branches(String token, Integer credentialType, String owner, String repo) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/branches";
        JsonNode root = getJson(url, token, credentialType);
        List<String> names = new ArrayList<>();
        for (JsonNode node : root) {
            names.add(node.path("name").asText());
        }
        return names;
    }

    @Override
    public List<CommitInfo> commits(String token, Integer credentialType, String owner, String repo, String branch) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/commits?sha="
                + UriUtils.encodeQueryParam(branch, StandardCharsets.UTF_8) + "&per_page=50";
        JsonNode root = getJson(url, token, credentialType);
        List<CommitInfo> list = new ArrayList<>();
        for (JsonNode node : root) {
            list.add(new CommitInfo(
                    node.path("sha").asText(),
                    node.path("commit").path("message").asText(),
                    node.path("commit").path("author").path("name").asText(),
                    node.path("commit").path("author").path("date").asText()));
        }
        return list;
    }

    @Override
    public List<String> changedFiles(String token, Integer credentialType, String owner, String repo, String base, String head) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/compare/" + base + "..." + head;
        JsonNode root = getJson(url, token, credentialType);
        List<String> files = new ArrayList<>();
        for (JsonNode node : root.path("files")) {
            files.add(node.path("filename").asText());
        }
        return files;
    }

    private JsonNode getJson(String url, String token, Integer credentialType) {
        try {
            return mapper.readTree(get(url, token, credentialType));
        } catch (Exception e) {
            throw new IllegalStateException("GitHub 响应解析失败: " + e.getMessage(), e);
        }
    }

    private String get(String url, String token, Integer credentialType) {
        String auth = authHeader(token, credentialType);
        if (auth == null) {
            return restClient.get().uri(url).retrieve().body(String.class);
        }
        return restClient.get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .retrieve().body(String.class);
    }

    /** 项目级 credential 优先（token→Bearer / 密码→Basic），其次全局兜底 token。 */
    private String authHeader(String token, Integer credentialType) {
        if (token != null && !token.isBlank()) {
            if (credentialType != null && credentialType == 2) {
                String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                return "Basic " + encoded;
            }
            return "Bearer " + token;
        }
        String fallback = properties.getToken();
        return (fallback == null || fallback.isBlank()) ? null : "Bearer " + fallback;
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
