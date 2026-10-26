package com.codereview.git;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@Component
@ConfigurationProperties(prefix = "git.http")
public class GitHttpProperties {
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 60;
}
