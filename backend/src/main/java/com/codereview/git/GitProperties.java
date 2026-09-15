package com.codereview.git;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 仓库宿主接入的地址配置（`git.*`，与既有的 `git.cache.*` 同一命名空间）。
 *
 * <p>这两个 base 原先写死在 {@code GitHubClient} 里，导致两个后果：一是部署时无法调整
 * （内网代理、镜像站、自建企业版都得改代码），二是**客户端无法被测试**——测试要指向本地桩
 * 就必须能覆写 base。现在改为构造注入 + 可配置，默认值与历史行为一致。
 *
 * <p>刻意用 `git.*` 而不是 `github.*`：这两个键表达的是"仓库宿主这一层"，
 * 将来接入 Gitea 时复用的正是它们（届时按 host profile 覆盖）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "git")
public class GitProperties {

    /** REST API 根（不带尾斜杠）。默认 GitHub 公共 API。 */
    private String apiBase = "https://api.github.com";

    /** 原始文件根（不带尾斜杠），仅在 Contents API 不可用时兜底。默认 GitHub raw。 */
    private String rawBase = "https://raw.githubusercontent.com";

    /** 去掉尾斜杠，避免调用方写成 `https://host/` 时拼出 `//repos/...`。 */
    public String normalizedApiBase() {
        return stripTrailingSlash(apiBase);
    }

    public String normalizedRawBase() {
        return stripTrailingSlash(rawBase);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("git.api-base / git.raw-base 不能为空");
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
