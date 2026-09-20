package com.codereview.git;

import java.util.ArrayList;
import java.util.List;

/**
 * 从仓库地址解析 host/owner/repo（支持 https 与 git@ 两种形式）。
 *
 * <p><b>为什么还要 baseUrl</b>：Gitea 常挂在子路径下（内网是 {@code http://host/gitea}），
 * 而它的 API 根是 {@code {站点根}/api/v1} —— 只留 {@code host} 会把 {@code /gitea} 前缀丢掉，
 * 拼出来的 API 地址必然是 404。{@code baseUrl} 保留 {@code scheme://host[:port][/子路径前缀]}，
 * 由 {@link #apiBase()} 在其后拼 {@code /api/v1}。
 *
 * <p>{@code git@host:owner/repo} 形式没有 HTTP 信息，{@code baseUrl} 为 {@code null}
 * —— 这类仓库必须靠 {@code git.gitea.api-base} 显式指定 API 根。
 */
public record GitRepoRef(String baseUrl, String host, String owner, String repo) {

    /** 兼容旧调用（不带 baseUrl）：Gitea 客户端会退化为配置的 {@code git.gitea.api-base}。 */
    public GitRepoRef(String host, String owner, String repo) {
        this(null, host, owner, repo);
    }

    /** API 根：{@code {baseUrl}/api/v1}；baseUrl 缺失（git@ 形式）时返回 null。 */
    public String apiBase() {
        return baseUrl == null || baseUrl.isBlank() ? null : baseUrl + "/api/v1";
    }

    public static GitRepoRef parse(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        String scheme;
        String host;
        String path;
        if (s.contains("://")) {
            scheme = s.substring(0, s.indexOf("://"));
            String rest = s.substring(s.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("无法解析仓库地址: " + url);
            }
            host = rest.substring(0, slash);
            path = rest.substring(slash + 1);
        } else if (s.startsWith("git@")) {
            scheme = null;
            String rest = s.substring(4);
            int colon = rest.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("无法解析仓库地址: " + url);
            }
            host = rest.substring(0, colon);
            path = rest.substring(colon + 1);
        } else {
            throw new IllegalArgumentException("无法解析仓库地址: " + url);
        }
        List<String> parts = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (!seg.isBlank()) {
                parts.add(seg);
            }
        }
        if (parts.size() < 2) {
            throw new IllegalArgumentException("无法解析仓库地址: " + url);
        }
        String owner = parts.get(parts.size() - 2);
        String repo = parts.get(parts.size() - 1);
        String prefix = String.join("/", parts.subList(0, parts.size() - 2));
        String baseUrl = scheme == null ? null : scheme + "://" + host + (prefix.isEmpty() ? "" : "/" + prefix);
        return new GitRepoRef(baseUrl, host, owner, repo);
    }
}
