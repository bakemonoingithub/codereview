package com.codereview.git;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Git 侧内存缓存（单实例；对应技术选型的 Caffeine）。
 * <p>
 * <b>只缓存不可变引用（commit sha）下的内容</b>——分支名会移动，按分支缓存会读到过期数据。
 * 键按 {@code owner/repo@ref} 构造，避免跨仓库串味。容量与 TTL 可配：
 * {@code git.cache.commit-detail-max-size} / {@code git.cache.raw-file-max-size} / {@code git.cache.expire-minutes}。
 */
@Component
public class GitCache {

    /** 形如 commit sha：7–40 位十六进制。 */
    private static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{7,40}$");

    private final Cache<String, CommitDetail> commitDetails;
    private final Cache<String, String> rawFiles;
    private final Cache<String, List<String>> changedFileLists;

    public GitCache(@Value("${git.cache.commit-detail-max-size:200}") long commitDetailMaxSize,
                    @Value("${git.cache.raw-file-max-size:500}") long rawFileMaxSize,
                    @Value("${git.cache.expire-minutes:30}") long expireMinutes) {
        Duration ttl = Duration.ofMinutes(Math.max(1, expireMinutes));
        this.commitDetails = Caffeine.newBuilder()
                .maximumSize(Math.max(1, commitDetailMaxSize))
                .expireAfterWrite(ttl)
                .build();
        this.rawFiles = Caffeine.newBuilder()
                .maximumSize(Math.max(1, rawFileMaxSize))
                .expireAfterWrite(ttl)
                .build();
        this.changedFileLists = Caffeine.newBuilder()
                .maximumSize(Math.max(1, commitDetailMaxSize))
                .expireAfterWrite(ttl)
                .build();
    }

    /** 引用是否不可变（可用于缓存）。分支名/标签名返回 false。 */
    public static boolean isImmutableRef(String ref) {
        return ref != null && SHA.matcher(ref.trim()).matches();
    }

    /** 缓存键：{@code owner/repo@ref}。 */
    public static String key(String owner, String repo, String ref) {
        return owner + "/" + repo + "@" + ref;
    }

    public CommitDetail commitDetail(String key, Supplier<CommitDetail> loader) {
        return commitDetails.get(key, k -> loader.get());
    }

    public String rawFile(String key, Supplier<String> loader) {
        return rawFiles.get(key, k -> loader.get());
    }

    /** 变更文件路径列表（仅当 base/head 都是不可变引用时才应调用）。 */
    public List<String> changedFiles(String key, Supplier<List<String>> loader) {
        return changedFileLists.get(key, k -> loader.get());
    }

    /** 清空（配置变更或排障时用）。 */
    public void clear() {
        commitDetails.invalidateAll();
        rawFiles.invalidateAll();
        changedFileLists.invalidateAll();
    }
}
