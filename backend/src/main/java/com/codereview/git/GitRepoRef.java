package com.codereview.git;

/**
 * 从仓库地址解析 host/owner/repo（支持 https 与 git@ 两种形式）
 */
public record GitRepoRef(String host, String owner, String repo) {

    public static GitRepoRef parse(String url) {
        String s = url == null ? "" : url.trim();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        String host;
        String path;
        if (s.contains("://")) {
            String rest = s.substring(s.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("无法解析仓库地址: " + url);
            }
            host = rest.substring(0, slash);
            path = rest.substring(slash + 1);
        } else if (s.startsWith("git@")) {
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
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("无法解析仓库地址: " + url);
        }
        return new GitRepoRef(host, parts[parts.length - 2], parts[parts.length - 1]);
    }
}
