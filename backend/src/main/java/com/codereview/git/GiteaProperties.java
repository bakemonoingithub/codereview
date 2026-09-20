package com.codereview.git;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Gitea 接入配置（{@code git.gitea.*}）。
 *
 * <p>与 {@link GitProperties}（GitHub 用）分开：GitHub 走固定域名，Gitea 是自建、可能多站点，
 * 且 API 根要从各项目的仓库地址推导（内网挂在 {@code /gitea} 子路径下）。
 *
 * <p>配置项都有合理默认值：不配 {@code hosts} 时只有本机开发地址 {@code localhost:3000} 归 Gitea，
 * 内网部署时把 {@code 192.104.224.172} 加进白名单即可 —— <b>未命中的 host 仍显式报
 * {@code GIT_HOST_UNSUPPORTED}，绝不猜</b>。
 */
@Data
@Component
@ConfigurationProperties(prefix = "git.gitea")
public class GiteaProperties {

    /** 哪些 host（{@code host[:port]}，精确匹配、大小写不敏感）归 Gitea 客户端。 */
    private List<String> hosts = new ArrayList<>(List.of("localhost:3000"));

    /**
     * 可选的 API 根覆盖（含 {@code /api/v1}）。留空时按仓库地址推导
     * （{@code scheme://host[:port][/子路径] + /api/v1}）—— 推荐留空。
     * 仅用于 {@code git@host:owner/repo} 这种拿不到 HTTP 信息的地址。
     */
    private String apiBase = "";

    /** 兜底令牌（建议经环境变量 GITEA_TOKEN 注入）。项目级 credential 优先。 */
    private String token = "";

    /** JGit 本地镜像目录；留空时用 {@code ${java.io.tmpdir}/code-review-git-mirrors}。 */
    private String workDir = "";

    /** 是否允许用本地镜像实现 Gitea 1.16.1 缺失的 compare。关闭时 changedFiles 直接报错。 */
    private boolean localCloneEnabled = true;

    /** 单次克隆/拉取的超时（秒）。 */
    private int mirrorTimeoutSeconds = 120;

    /** 单个提交 diff 文本上限（字节）；超限时整提交 patch 置空而不是把半截结果当全量。 */
    private long diffMaxBytes = 5L * 1024 * 1024;

    /** 去掉尾斜杠；留空返回 null（表示"按仓库地址推导"）。 */
    public String normalizedApiBase() {
        if (apiBase == null || apiBase.isBlank()) {
            return null;
        }
        String trimmed = apiBase.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 本地镜像根目录（绝对路径）。 */
    public File resolvedWorkDir() {
        String dir = (workDir == null || workDir.isBlank())
                ? System.getProperty("java.io.tmpdir") + File.separator + "code-review-git-mirrors"
                : workDir.trim();
        return new File(dir);
    }

    /** 兜底令牌；未配置返回 null。 */
    public String fallbackToken() {
        return (token == null || token.isBlank()) ? null : token.trim();
    }
}
