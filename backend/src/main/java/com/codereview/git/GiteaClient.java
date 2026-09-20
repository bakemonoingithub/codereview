package com.codereview.git;

import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Gitea 实现（对 1.16.1 的实测差异逐条适配，依据见
 * {@code docs/gitea-adaption/Gitea-1.16.1-API-与-GitHub-差异调研.md}）。
 *
 * <p>与 GitHub 的关键差异（每一条都对应下面一处实现，不是"顺手写的"）：
 * <ul>
 *   <li><b>API 根按项目推导</b>：{@code {仓库地址的 scheme://host[:port][/子路径]}/api/v1}，
 *       内网挂在 {@code /gitea} 下也能拼对；配置 {@code git.gitea.api-base} 可覆盖；</li>
 *   <li><b>列表分页参数是 {@code limit} 不是 {@code per_page}</b>（上限 50），
 *       翻页信号是 {@code X-Total-Count} / {@code Link}；<b>Link 里是绝对 URL 且基于 Gitea 的
 *       ROOT_URL，内网配错时会指向 localhost</b> —— 只当 hasNext 用，绝不 follow；</li>
 *   <li><b>文件树单页上限 1000 条</b>：不翻页会静默丢文件（GitHub 一次给全，所以这是新引入的坑）；</li>
 *   <li><b>分支哈希字段是 {@code commit.id}</b>（GitHub 是 {@code commit.sha}），
 *       照搬会拿到空串；</li>
 *   <li><b>路径段不吃含 {@code /} 的分支名</b>：先用 {@code /commits?sha=} 解析成 sha 再请求；</li>
 *   <li><b>无 compare 接口</b>、<b>无 JSON 形式的逐文件 patch</b>：见 {@link #changedFiles} 与
 *       {@link #commitDetail}。</li>
 * </ul>
 *
 * <p>缓存策略与 GitHub 侧一致：只缓存<b>不可变引用（commit sha）</b>下的内容。
 */
@Component
public class GiteaClient implements GitHostClient {

    private static final Logger LOG = LoggerFactory.getLogger(GiteaClient.class);

    /** Gitea 文件树单页上限（[api] DEFAULT_GIT_TREES_PER_PAGE） */
    private static final int TREE_PER_PAGE = 1000;
    /** 翻页硬上限：50 万条目，防"服务端始终回满页"时打爆内存。 */
    private static final int MAX_TREE_PAGES = 500;
    /** Gitea 列表接口每页上限（[api] MAX_RESPONSE_ITEMS）与默认值 */
    private static final int LIST_LIMIT = 50;
    private static final int MAX_LIST_PAGES = 200;

    private final GiteaProperties properties;
    private final GitCache cache;
    private final GiteaGitMirror mirror;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GiteaClient(GiteaProperties properties, GitCache cache, GiteaGitMirror mirror) {
        this.properties = properties;
        this.cache = cache;
        this.mirror = mirror;
        this.restClient = restClient();
    }

    @Override
    public Set<String> hosts() {
        List<String> hosts = properties.getHosts();
        return hosts == null ? Set.of() : new LinkedHashSet<>(hosts);
    }

    // ---------------------------------------------------------------- 文件树

    @Override
    public List<GitTreeEntry> tree(String token, Integer credentialType, GitRepoRef repoRef, String branch) {
        String api = apiBase(repoRef);
        String ref = resolvePathRef(token, credentialType, repoRef, branch);
        List<GitTreeEntry> entries = new ArrayList<>();
        for (int page = 1; page <= MAX_TREE_PAGES; page++) {
            String url = api + "/repos/" + repoRef.owner() + "/" + repoRef.repo() + "/git/trees/"
                    + UriUtils.encodePathSegment(ref, StandardCharsets.UTF_8)
                    + "?recursive=true&per_page=" + TREE_PER_PAGE + "&page=" + page;
            JsonNode root = getJson(url, token, credentialType);
            JsonNode tree = root.path("tree");
            if (!tree.isArray() || tree.isEmpty()) {
                break;
            }
            int returned = 0;
            for (JsonNode node : tree) {
                returned++;
                String type = node.path("type").asText();
                if ("blob".equals(type) || "tree".equals(type)) {
                    entries.add(new GitTreeEntry(node.path("path").asText(), type));
                }
            }
            // 不足一页即最后一页；满了就继续翻（Gitea 不会一次给全）
            if (returned < TREE_PER_PAGE) {
                break;
            }
        }
        return entries;
    }

    // ---------------------------------------------------------------- 文件内容

    @Override
    public String rawFile(String token, Integer credentialType, GitRepoRef repoRef, String ref, String path) {
        if (GitCache.isImmutableRef(ref)) {
            String key = GitCache.key(repoRef.owner(), repoRef.repo(), ref) + ":" + path;
            return cache.rawFile(key, () -> rawFileAtRef(token, credentialType, repoRef, ref, path));
        }
        return rawFileAtRef(token, credentialType, repoRef, ref, path);
    }

    private String rawFileAtRef(String token, Integer credentialType, GitRepoRef repoRef, String ref, String path) {
        String api = apiBase(repoRef);
        String contentsUrl = api + "/repos/" + repoRef.owner() + "/" + repoRef.repo() + "/contents/"
                + encodePathSegments(path) + "?ref=" + UriUtils.encodeQueryParam(ref, StandardCharsets.UTF_8);
        JsonNode root = getJson(contentsUrl, token, credentialType);
        String encoding = root.path("encoding").asText();
        String content = root.path("content").asText();
        // 注意：Gitea 对超过 [api] DEFAULT_MAX_BLOB_SIZE（默认 10MiB）的文件仍回 200 + base64，
        // 但 content 为空 —— 所以判空后要走 raw，不能拿 encoding 当"取到了"的证据。
        if ("base64".equalsIgnoreCase(encoding) && !content.isBlank()) {
            try {
                return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 解码失败时回退 raw
            }
        }
        String rawUrl = api + "/repos/" + repoRef.owner() + "/" + repoRef.repo() + "/raw/"
                + encodePathSegments(path) + "?ref=" + UriUtils.encodeQueryParam(ref, StandardCharsets.UTF_8);
        return get(rawUrl, token, credentialType);
    }

    // ---------------------------------------------------------------- 分支与提交

    @Override
    public String headCommitSha(String token, Integer credentialType, GitRepoRef repoRef, String branch) {
        if (branch != null && branch.contains("/")) {
            // 含斜杠的分支不能放进路径段（chi 路由按单段匹配），用查询参数版本解析
            List<CommitInfo> first = commitPage(token, credentialType, repoRef, branch, 1, 1).commits();
            return first.isEmpty() || first.get(0).sha() == null ? "" : first.get(0).sha();
        }
        String url = apiBase(repoRef) + "/repos/" + repoRef.owner() + "/" + repoRef.repo() + "/branches/"
                + UriUtils.encodePathSegment(branch, StandardCharsets.UTF_8);
        JsonNode root = getJson(url, token, credentialType);
        // Gitea 的 PayloadCommit 用 id（GitHub 是 sha），照抄 GitHub 会拿到空串
        return root.path("commit").path("id").asText();
    }

    @Override
    public List<String> branches(String token, Integer credentialType, GitRepoRef repoRef) {
        List<String> names = new ArrayList<>();
        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            String url = apiBase(repoRef) + "/repos/" + repoRef.owner() + "/" + repoRef.repo()
                    + "/branches?page=" + page + "&limit=" + LIST_LIMIT;
            ResponseEntity<String> resp = getEntity(url, token, credentialType);
            JsonNode root = readTree(resp.getBody());
            if (!root.isArray() || root.isEmpty()) {
                break;
            }
            for (JsonNode node : root) {
                String name = node.path("name").asText();
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            // Gitea 默认每页 30，只有显式传 limit 才是 50；不足一页即到底
            if (root.size() < LIST_LIMIT || !hasMorePages(resp, page, LIST_LIMIT)) {
                break;
            }
        }
        return names;
    }

    @Override
    public List<CommitInfo> commits(String token, Integer credentialType, GitRepoRef repoRef, String branch) {
        return commitPage(token, credentialType, repoRef, branch, 1, LIST_LIMIT).commits();
    }

    @Override
    public CommitPage commitPage(String token, Integer credentialType, GitRepoRef repoRef,
                                 String branch, int page, int perPage) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, perPage), LIST_LIMIT);
        String url = apiBase(repoRef) + "/repos/" + repoRef.owner() + "/" + repoRef.repo() + "/commits?sha="
                + UriUtils.encodeQueryParam(branch, StandardCharsets.UTF_8)
                + "&page=" + safePage + "&limit=" + safeSize;
        ResponseEntity<String> resp = getEntity(url, token, credentialType);
        JsonNode root = readTree(resp.getBody());
        List<CommitInfo> list = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                list.add(GitResponseParser.parseCommitInfo(node));
            }
        }
        return new CommitPage(list, hasMorePages(resp, safePage, safeSize));
    }

    /**
     * 是否还有下一页。**只把 Link 当信号，不 follow 它的绝对 URL** ——
     * Gitea 用 ROOT_URL 拼这个地址，内网 ROOT_URL 配成 localhost 时会指向错误主机。
     */
    private static boolean hasMorePages(ResponseEntity<String> resp, int page, int pageSize) {
        if (GitResponseParser.hasNextPage(resp.getHeaders().getFirst(HttpHeaders.LINK))) {
            return true;
        }
        String total = resp.getHeaders().getFirst("X-Total-Count");
        if (total == null || total.isBlank()) {
            return false;
        }
        try {
            return (long) page * pageSize < Long.parseLong(total.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 单提交详情

    @Override
    public CommitDetail commitDetail(String token, Integer credentialType, GitRepoRef repoRef, String sha) {
        return cache.commitDetail(GitCache.key(repoRef.owner(), repoRef.repo(), sha),
                () -> fetchCommitDetail(token, credentialType, repoRef, sha));
    }

    private CommitDetail fetchCommitDetail(String token, Integer credentialType, GitRepoRef repoRef, String sha) {
        String api = apiBase(repoRef);
        String base = api + "/repos/" + repoRef.owner() + "/" + repoRef.repo() + "/git/commits/"
                + UriUtils.encodePathSegment(sha, StandardCharsets.UTF_8);
        JsonNode root = getJson(base, token, credentialType);
        // Gitea 的 files[] 只有 filename（没有 patch/status/统计），patch 必须另取 .diff 文本
        CommitDetail meta = GitResponseParser.parseCommit(root, false);
        List<ChangedFile> files = meta.files();

        String diffText;
        try {
            diffText = get(base + ".diff", token, credentialType);
        } catch (Exception e) {
            throw new IllegalStateException("Gitea 单提交 diff 获取失败（" + sha + "）：" + e.getMessage(), e);
        }
        if (diffText == null || diffText.isBlank()) {
            return meta;
        }
        if (diffText.length() > properties.getDiffMaxBytes()) {
            // 超限时保留文件清单、整提交 patch 置空：宁可让上层走"全文件兜底"，
            // 也不能把半截 patch 当成完整 diff 交给审查链路
            LOG.warn("Gitea 提交 {} 的 diff 文本 {} 字节超过上限 {}，本次全部 patch 置空",
                    sha, diffText.length(), properties.getDiffMaxBytes());
            return meta;
        }
        List<ChangedFile> parsed = GiteaDiffParser.parse(diffText);
        if (!parsed.isEmpty()) {
            files = parsed;
        }
        int additions = sum(files, true);
        int deletions = sum(files, false);
        return new CommitDetail(meta.sha(), meta.parents(), meta.message(), meta.author(), meta.date(),
                additions, deletions, additions + deletions, false, files);
    }

    /** 汇总统计；Gitea 不提供，按逐文件自数的结果相加（二进制文件为 null，跳过）。 */
    private static int sum(List<ChangedFile> files, boolean additions) {
        int total = 0;
        for (ChangedFile file : files) {
            Integer value = additions ? file.additions() : file.deletions();
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- 变更文件（compare）

    @Override
    public List<String> changedFiles(String token, Integer credentialType, GitRepoRef repoRef,
                                     String base, String head) {
        if (!properties.isLocalCloneEnabled()) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                    "Gitea 1.16.1 没有 compare 接口，而本地镜像（git.gitea.local-clone-enabled）已关闭，"
                            + "无法列出 " + base + "..." + head + " 的变更文件");
        }
        // 仅当两端都是 commit sha 时才缓存（任一端是分支名就可能在移动）
        if (GitCache.isImmutableRef(base) && GitCache.isImmutableRef(head)) {
            String key = GitCache.key(repoRef.owner(), repoRef.repo(), base + "..." + head);
            return cache.changedFiles(key, () -> mirror.changedPaths(cloneUrl(repoRef), token, credentialType, base, head));
        }
        return mirror.changedPaths(cloneUrl(repoRef), token, credentialType, base, head);
    }

    /**
     * 本地镜像用的克隆地址：{@code {站点根}/{owner}/{repo}.git}。
     * 站点根优先取仓库地址里的（含子路径），其次从配置的 API 根去掉 {@code /api/v1} 反推。
     */
    private String cloneUrl(GitRepoRef repoRef) {
        String siteRoot = repoRef.baseUrl();
        if (siteRoot == null || siteRoot.isBlank()) {
            String api = properties.normalizedApiBase();
            if (api != null && api.endsWith("/api/v1")) {
                siteRoot = api.substring(0, api.length() - "/api/v1".length());
            }
        }
        if (siteRoot == null || siteRoot.isBlank()) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                    "无法确定 Gitea 站点根以下载本地镜像（" + repoRef.host()
                            + "）：请使用 http(s) 形式的仓库地址，或配置 git.gitea.api-base");
        }
        return siteRoot + "/" + repoRef.owner() + "/" + repoRef.repo() + ".git";
    }

    // ---------------------------------------------------------------- HTTP 基础设施

    /**
     * 解析本仓库的 API 根：优先配置，其次按仓库地址推导（保留 scheme/端口/子路径）。
     * 两者都没有（{@code git@host:owner/repo}）时显式报错，绝不猜一个地址出来。
     */
    private String apiBase(GitRepoRef repoRef) {
        String configured = properties.normalizedApiBase();
        if (configured != null) {
            return configured;
        }
        String derived = repoRef == null ? null : repoRef.apiBase();
        if (derived == null || derived.isBlank()) {
            throw new BusinessException(ResultCode.GIT_CONNECT_FAILED.getCode(),
                    "无法从仓库地址推导 Gitea API 根（" + (repoRef == null ? "<空>" : repoRef.host())
                            + "）：请改用 http(s) 形式的仓库地址，或配置 git.gitea.api-base");
        }
        return derived;
    }

    /** Gitea 路径参数只吃单个路径段；含 {@code /} 的分支名先解析成 sha。 */
    private String resolvePathRef(String token, Integer credentialType, GitRepoRef repoRef, String branch) {
        if (branch == null || branch.isBlank() || (!branch.contains("/") && !branch.contains("?"))) {
            return branch;
        }
        try {
            List<CommitInfo> first = commitPage(token, credentialType, repoRef, branch, 1, 1).commits();
            if (!first.isEmpty() && first.get(0).sha() != null && !first.get(0).sha().isBlank()) {
                return first.get(0).sha();
            }
        } catch (Exception e) {
            // 解析不出来就退回原样请求：让服务端给出真实的 4xx，而不是在这里吞掉
        }
        return branch;
    }

    private JsonNode getJson(String url, String token, Integer credentialType) {
        return readTree(get(url, token, credentialType));
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Gitea 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 需要读响应头（分页 / 总数）时用这个。 */
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
     * 必须传 {@link URI} 而不是 String：传 String 时 Spring 会把已编码的 URL 当模板再编一遍，
     * {@code feature%2Fx} 会变成 {@code feature%252Fx}（GitHub 侧踩过，见
     * {@code GitHubClientBranchEncodingTest}）。**新增调用点务必自行编码。**
     */
    private static URI encodedUri(String url) {
        return URI.create(url);
    }

    /** 项目级 credential 优先（token→token 头 / 密码→Basic），其次配置兜底 token。 */
    private String authHeader(String token, Integer credentialType) {
        if (token != null && !token.isBlank()) {
            if (credentialType != null && credentialType == 2) {
                String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                return "Basic " + encoded;
            }
            // Gitea 文档写的是 "token" 前缀（Bearer 也接受，但按文档来更稳）
            return "token " + token;
        }
        String fallback = properties.fallbackToken();
        return fallback == null ? null : "token " + fallback;
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
