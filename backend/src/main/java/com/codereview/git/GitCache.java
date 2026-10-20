package com.codereview.git;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Git 侧内存缓存（单实例；对应技术选型的 Caffeine）。
 * <p>
 * <b>只缓存不可变引用（commit sha）下的内容</b>——分支名会移动，按分支缓存会读到过期数据。
 * 键包含宿主、{@code owner/repo}、凭据指纹与 ref：同名仓库放在不同宿主、或换了令牌之后，
 * 都不会命中彼此的内容。凭据只以 SHA-256 前缀出现在键里，不保存原文。
 * 容量与 TTL 可配：
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

    /**
     * 缓存键：{@code host/owner/repo#<凭据指纹>@ref}。
     *
     * <p>凭据指纹让"换令牌后仍读到旧内容"不可能发生；宿主参与键计算，
     * 避免内网 Gitea 与同名的其他站点互相串用。
     */
    public static String key(GitRepoRef repo, String credential, String ref) {
        String token = credential == null || credential.isBlank() ? "<none>" : credential;
        return repo.host() + "/" + repo.owner() + "/" + repo.repo() + "#" + fingerprint(token) + "@" + ref;
    }

    /** 凭据指纹：SHA-256 前 16 位十六进制；空白凭据固定为 anon。 */
    public static String fingerprint(String credential) {
        if (credential == null || credential.isBlank()) {
            return "anon";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(credential.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
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
