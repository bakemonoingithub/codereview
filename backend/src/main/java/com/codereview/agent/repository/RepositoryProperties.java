package com.codereview.agent.repository;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@Component
@ConfigurationProperties(prefix = "agent.repository")
public class RepositoryProperties {
    private int maxFileBytes = 4194304;
    private long fileCacheWeightBytes = 134217728L;
    private int fileCacheMinutes = 30;
    private int treeCacheSize = 256;
    private int treeCacheMinutes = 10;
    private java.util.Set<String> allowedExtensions = new java.util.LinkedHashSet<>(com.codereview.common.ReviewableFiles.extensions());
}
