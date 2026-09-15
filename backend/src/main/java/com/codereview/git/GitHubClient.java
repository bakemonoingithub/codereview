package com.codereview.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * GitHub 实现；token 可空（项目级 credential 为空时回退 github.token 兜底认证，
 * 两者都为空才匿名访问公开仓库，但仅 60 次/小时/IP）。
 * credentialType=2 时按账号密码走 Basic Auth（credential 形如 user:pass）。
 * <p>
 * 缓存策略：只缓存<b>不可变引用（commit sha）</b>下的内容——分支名会移动，按分支缓存会读到过期数据。
 */
@Component
public class GitHubClient implements GitHostClient {

    /** GitHub per_page 上限为 100 */
    private static final int MAX_PER_PAGE = 100;
    private static final int LEGACY_COMMIT_PER_PAGE = 50;

    private final GitHubProperties properties;
    private final String apiBase;
    private final String rawBase;
    private final GitCache cache;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubClient(GitHubProperties properties, GitProperties gitProperties, GitCache cache) {
        this.properties = properties;
        this.apiBase = gitProperties.normalizedApiBase();
        this.rawBase = gitProperties.normalizedRawBase();
        this.cache = cache;
        this.restClient = restClient();
    }

    @Override
    public List<GitTreeEntry> tree(String token, Integer credentialType, String owner, String repo, String branch) {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/git/trees/" + branch + "?recursive=1";
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
    public String rawFile(String token, Integer credentialType, String owner, String repo, String ref, String path) {
        if (GitCache.isImmutableRef(ref)) {
            String key = GitCache.key(owner, repo, ref) + ":" + path;
            return cache.rawFile(key, () -> rawFileAtRef(token, credentialType, owner, repo, ref, path));
        }
        return rawFileAtRef(token, credentialType, owner, repo, ref, path);
    }

    private String rawFileAtRef(String token, Integer credentialType, String owner, String repo, String ref, String path) {
        // 优先走 Contents API（与树接口同域 api.github.com），避免 raw.githubusercontent.com 直连被墙/超时。
        // Contents API 的 ref 接受分支名、标签或 commit sha。
        String contentsUrl = apiBase + "/repos/" + owner + "/" + repo + "/contents/" + encodePathSegments(path)
                + "?ref=" + UriUtils.encodeQueryParam(ref, StandardCharsets.UTF_8);
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
        String rawUrl = rawBase + "/" + owner + "/" + repo + "/" + encodePathSegments(ref) + "/" + encodePathSegments(path);
        return get(rawUrl, token, credentialType);
    }

    @Override
    public String headCommitSha(String token, Integer credentialType, String owner, String repo, String branch) {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/branches/"
                + UriUtils.encodePathSegment(branch, StandardCharsets.UTF_8);
        JsonNode root = getJson(url, token, credentialType);
        return root.path("commit").path("sha").asText();
    }

    @Override
    public List<String> branches(String token, Integer credentialType, String owner, String repo) {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/branches";
        JsonNode root = getJson(url, token, credentialType);
        List<String> names = new ArrayList<>();
        for (JsonNode node : root) {
            names.add(node.path("name").asText());
        }
        return names;
    }

    @Override
    public List<CommitInfo> commits(String token, Integer credentialType, String owner, String repo, String branch) {
        return commitPage(token, credentialType, owner, repo, branch, 1, LEGACY_COMMIT_PER_PAGE).commits();
    }

    @Override
    public CommitPage commitPage(String token, Integer credentialType, String owner, String repo,
                                 String branch, int page, int perPage) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, perPage), MAX_PER_PAGE);
        String url = apiBase + "/repos/" + owner + "/" + repo + "/commits?sha="
                + UriUtils.encodeQueryParam(branch, StandardCharsets.UTF_8)
                + "&page=" + safePage + "&per_page=" + safeSize;
        ResponseEntity<String> resp = getEntity(url, token, credentialType);
        JsonNode root = readTree(resp.getBody());
        List<CommitInfo> list = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                list.add(GitResponseParser.parseCommitInfo(node));
            }
        }
        return new CommitPage(list, GitResponseParser.hasNextPage(linkHeader(resp)));
    }

    @Override
    public List<String> changedFiles(String token, Integer credentialType, String owner, String repo,
                                     String base, String head) {
        // 仅当两端都是 commit sha 时才缓存（任一端是分支名就可能在移动）
        if (GitCache.isImmutableRef(base) && GitCache.isImmutableRef(head)) {
            String key = GitCache.key(owner, repo, base + "..." + head);
            return cache.changedFiles(key, () -> fetchChangedFiles(token, credentialType, owner, repo, base, head));
        }
        return fetchChangedFiles(token, credentialType, owner, repo, base, head);
    }

    private List<String> fetchChangedFiles(String token, Integer credentialType, String owner, String repo,
                                           String base, String head) {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/compare/"
                + UriUtils.encodePathSegment(base, StandardCharsets.UTF_8) + "..."
                + UriUtils.encodePathSegment(head, StandardCharsets.UTF_8);
        JsonNode root = getJson(url, token, credentialType);
        List<String> files = new ArrayList<>();
        for (JsonNode node : root.path("files")) {
            String filename = node.path("filename").asText();
            if (!filename.isBlank()) {
                files.add(filename);
            }
        }
        return files;
    }

    @Override
    public CommitDetail commitDetail(String token, Integer credentialType, String owner, String repo, String sha) {
        return cache.commitDetail(GitCache.key(owner, repo, sha),
                () -> fetchCommitDetail(token, credentialType, owner, repo, sha));
    }

    private CommitDetail fetchCommitDetail(String token, Integer credentialType, String owner, String repo, String sha) {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/commits/"
                + UriUtils.encodePathSegment(sha, StandardCharsets.UTF_8);
        ResponseEntity<String> resp = getEntity(url, token, credentialType);
        // GitHub 单提交接口每页最多 300 个文件，还有更多时通过 Link 头给出 rel="next"
        boolean truncated = GitResponseParser.hasNextPage(linkHeader(resp));
        return GitResponseParser.parseCommit(readTree(resp.getBody()), truncated);
    }

    private static String linkHeader(ResponseEntity<String> resp) {
        return resp.getHeaders().getFirst(HttpHeaders.LINK);
    }

    private JsonNode getJson(String url, String token, Integer credentialType) {
        return readTree(get(url, token, credentialType));
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("GitHub 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 需要读响应头（Link 分页）时用这个。 */
    private ResponseEntity<String> getEntity(String url, String token, Integer credentialType) {
        String auth = authHeader(token, credentialType);
        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(encodedUri(url));
        if (auth != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, auth);
        }
        return spec.retrieve().toEntity(String.class);
    }

    private String get(String url, String token, Integer credentialType) {
        String auth = authHeader(token, credentialType);
        if (auth == null) {
            return restClient.get().uri(encodedUri(url)).retrieve().body(String.class);
        }
        return restClient.get().uri(encodedUri(url))
                .header(HttpHeaders.AUTHORIZATION, auth)
                .retrieve().body(String.class);
    }

    /**
     * 把**已经按段编码好的** URL 原样交给 RestClient。
     *
     * <p>必须传 {@link URI} 而不是 `String`：传 String 时 Spring 会把它当成 URI 模板再编码一遍，
     * 于是 `feature/x` 编码成的 `feature%2Fx` 被二次编码成 `feature%252Fx`
     * ——服务端解出来是字面量 `feature%2Fx`，**分支名含斜杠的请求必然 404**。
     * 后果不只是报错：`ReviewService.resolveCommitSha` 对失败是"日志警告 + 返回 null"，
     * 于是记录里 `commit_sha` 静默为空、审查按会移动的分支名取内容，结果不可复现。
     *
     * <p>前提：调用方必须已经完成编码（各方法用的是 {@code UriUtils.encodePathSegment} /
     * {@code encodeQueryParam} / {@code encodePathSegments}）。传 {@code URI} 后 Spring 不再插手，
     * 所以**新增调用点时务必自己编码**。
     */
    private static URI encodedUri(String url) {
        return URI.create(url);
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
