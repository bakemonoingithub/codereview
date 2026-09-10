package com.codereview.git;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub REST API 接入配置（可被 application.yml 的 github.* 覆盖）。
 * token 作为“兜底认证”：项目未配置 credential（或为空）时自动使用该令牌请求，
 * 认证后限额 5000 次/小时，远高于匿名限额 60 次/小时/IP。
 */
@Data
@Component
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    /** 兜底 Personal Access Token（建议经环境变量 GITHUB_TOKEN 注入，避免明文入库） */
    private String token = "";
}
