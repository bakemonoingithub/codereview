package com.codereview.git;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@Component
@ConfigurationProperties(prefix = "git.github")
public class GitHubHostProperties {
    private java.util.Set<String> hosts = new java.util.LinkedHashSet<>(java.util.Set.of("github.com"));
}
