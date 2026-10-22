package com.codereview.git;

import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 受控共享 bare mirror：克隆、拉取、按固定对象提供只读使用句柄。
 *
 * <p>从 {@link GiteaGitMirror} 提炼出来的通用能力，供 Gitea 差异比较与 Agent 只读检索共用：
 * 同一 cloneUrl 在本地只有一份镜像，固定对象已存在时不逐次 fetch；需要新对象时在同一把锁里
 * 拉取一次。读取使用读锁，克隆/拉取使用写锁，避免一边 fetch 一边被读取。
 *
 * <p>凭据只交给 JGit 的 {@link CredentialsProvider}，不进入镜像地址、目录名或诊断正文。
 * 不同站点同名仓库通过 cloneUrl 哈希分目录，不会互相污染。
 */
public class GitMirror {

    private static final Logger LOG = LoggerFactory.getLogger(GitMirror.class);

    /** 进程内按镜像目录串行；跨实例共享，避免两个使用方各拿一把锁同时操作同一目录。 */
    private static final ConcurrentMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    private final File workDir;
    private final int timeoutSeconds;

    public GitMirror(GiteaProperties properties) {
        this(properties.resolvedWorkDir(), properties.getMirrorTimeoutSeconds());
    }

    public GitMirror(File workDir, int timeoutSeconds) {
        this.workDir = workDir;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
    }

    public File mirrorDir(String cloneUrl) {
        String hash = shortHash(cloneUrl);
        String readable = cloneUrl.replaceAll("[^A-Za-z0-9._-]", "_");
        if (readable.length() > 80) {
            readable = readable.substring(readable.length() - 80);
        }
        return new File(workDir, hash + "-" + readable);
    }

    /**
     * 在镜像存在且所需对象已就绪时直接读取；否则先克隆或拉取一次。
     *
     * @param requiredObjects 读取前必须存在的对象（完整 SHA）；非 SHA 的引用（如分支名）会触发一次拉取
     * @param maxWait         本次操作可等待的上限；与配置的单次镜像超时取较小值
     */
    public <T> T using(String cloneUrl, String credential, Integer credentialType,
                       List<String> requiredObjects, Duration maxWait, Work<T> work) {
        if (cloneUrl == null || cloneUrl.isBlank()) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(), "缺少仓库地址");
        }
        List<String> required = requiredObjects == null ? List.of() : requiredObjects;
        File dir = mirrorDir(cloneUrl);
        ReentrantReadWriteLock lock = LOCKS.computeIfAbsent(dir.getAbsolutePath(),
                key -> new ReentrantReadWriteLock());
        boolean needsRefresh = required.stream().anyMatch(object -> !isFullSha(object));

        if (!needsRefresh && lock.readLock().tryLock()) {
            try {
                if (dirReady(dir) && required.stream().allMatch(object -> hasObject(dir, object))) {
                    return run(work, dir);
                }
            } finally {
                lock.readLock().unlock();
            }
        }

        lock.writeLock().lock();
        try {
            boolean created = ensureMirror(dir, cloneUrl, credential, credentialType, maxWait);
            if (!created) {
                fetch(dir, credential, credentialType, maxWait);
            }
            for (String object : required) {
                if (isFullSha(object) && !hasObject(dir, object)) {
                    throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                            "本地镜像里找不到引用 " + object + "（" + cloneUrl + "）");
                }
            }
            lock.readLock().lock();
        } finally {
            lock.writeLock().unlock();
        }
        try {
            return run(work, dir);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 镜像是否已初始化（bare 仓库有 HEAD 且有对象库）。 */
    public boolean dirReady(File dir) {
        return new File(dir, "HEAD").exists();
    }

    /** 固定对象（提交）是否已在本地镜像中；只查对象库，不解析分支名。 */
    public boolean hasObject(File dir, String sha) {
        if (sha == null || !isFullSha(sha) || !dirReady(dir)) {
            return false;
        }
        try (org.eclipse.jgit.lib.Repository repo =
                     new FileRepositoryBuilder().setGitDir(dir).build()) {
            org.eclipse.jgit.lib.ObjectId id =
                    org.eclipse.jgit.lib.ObjectId.fromString(sha.toLowerCase());
            return repo.getObjectDatabase().has(id);
        } catch (Exception e) {
            return false;
        }
    }

    /** @return true 表示本次新建了镜像（无需再拉取） */
    private boolean ensureMirror(File dir, String cloneUrl, String credential,
                                 Integer credentialType, Duration maxWait) {
        if (dirReady(dir)) {
            return false;
        }
        try {
            java.nio.file.Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                    "无法创建镜像目录（" + dir + "）：" + message(e));
        }
        LOG.info("本地镜像首次克隆: {} -> {}", cloneUrl, dir);
        CloneCommand clone = Git.cloneRepository()
                .setURI(cloneUrl)
                .setDirectory(dir)
                .setBare(true)
                .setMirror(true)
                .setTimeout(timeoutSeconds(maxWait));
        CredentialsProvider provider = credentials(credential, credentialType);
        if (provider != null) {
            clone.setCredentialsProvider(provider);
        }
        try (Git ignored = clone.call()) {
            LOG.info("本地镜像克隆完成: {}", dir);
            return true;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                    "本地镜像克隆失败（" + cloneUrl + "）：" + message(e));
        }
    }

    private void fetch(File dir, String credential, Integer credentialType, Duration maxWait) {
        try (Git git = Git.open(dir)) {
            FetchCommand fetch = git.fetch().setRemote("origin").setTimeout(timeoutSeconds(maxWait));
            CredentialsProvider provider = credentials(credential, credentialType);
            if (provider != null) {
                fetch.setCredentialsProvider(provider);
            }
            fetch.call();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                    "本地镜像拉取失败（" + dir.getName() + "）：" + message(e));
        }
    }

    private int timeoutSeconds(Duration maxWait) {
        if (maxWait == null || maxWait.isNegative() || maxWait.isZero()) {
            return timeoutSeconds;
        }
        long seconds = Math.max(1, maxWait.toSeconds());
        return (int) Math.min(timeoutSeconds, seconds);
    }

    private static <T> T run(Work<T> work, File dir) {
        try {
            return work.run(dir);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                    "本地镜像读取失败：" + message(e));
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static boolean isFullSha(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}");
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算镜像目录名: " + e.getMessage(), e);
        }
    }

    /**
     * JGit 凭据：Gitea 的 Basic 认证先把密码当令牌查，所以令牌放密码位；
     * credentialType=2 时按第一个冒号拆成 user:pass。凭据不写入 URL 或日志。
     */
    private static CredentialsProvider credentials(String credential, Integer credentialType) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        if (credentialType != null && credentialType == 2) {
            int index = credential.indexOf(':');
            String user = index > 0 ? credential.substring(0, index) : credential;
            String pass = index > 0 ? credential.substring(index + 1) : "";
            return new UsernamePasswordCredentialsProvider(user, pass);
        }
        return new UsernamePasswordCredentialsProvider("oauth2", credential);
    }

    /** 在镜像目录内执行的只读工作。 */
    @FunctionalInterface
    public interface Work<T> {
        T run(File mirrorDir) throws Exception;
    }
}
