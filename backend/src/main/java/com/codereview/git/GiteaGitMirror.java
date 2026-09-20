package com.codereview.git;

import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Gitea 1.16.1 没有 compare 接口，两个引用之间的变更文件只能自己算。
 *
 * <p>做法：每个仓库在本地留一份 <b>bare mirror</b>（{@code git clone --mirror} 的等价物），
 * 按需 fetch，然后用 <b>merge-base 三点语义</b> {@code git diff base...head} 算变更文件 ——
 * 与 GitHub compare 的语义一致（直接拿 base 树与 head 树比会多出 base 侧独有的变更）。
 *
 * <p>为什么不是"每次临时克隆"：验收指标要求 5 个 900 行文件的分析在 1 小时内完成，
 * 每次全量传一遍仓库会把预算吃光。为什么不是浅克隆：{@code base} 的历史常不在本地，
 * merge-base 会直接失败（或被迫再 fetch，语义变脆）。
 *
 * <p>并发：同一仓库的克隆/拉取/算差异在进程内 <b>按仓库串行</b>（JGit 对同一目录并发操作不安全），
 * 不同仓库互不阻塞。拉取失败/引用解析不出来一律<b>显式报错</b>，绝不返回空列表冒充"没有变更"。
 */
@Component
public class GiteaGitMirror {

    private static final Logger LOG = LoggerFactory.getLogger(GiteaGitMirror.class);

    private final GiteaProperties properties;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public GiteaGitMirror(GiteaProperties properties) {
        this.properties = properties;
    }

    /**
     * {@code base...head} 的变更文件路径（merge-base 三点语义，开启重命名检测）。
     *
     * @throws BusinessException {@code GIT_COMPARE_FAILED}：克隆/拉取/解析失败，或未开启本地镜像
     */
    public List<String> changedPaths(String cloneUrl, String credential, Integer credentialType,
                                     String base, String head) {
        if (base == null || base.isBlank() || head == null || head.isBlank()) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(), "缺少 base/head 引用");
        }
        File dir = mirrorDir(cloneUrl);
        Object lock = locks.computeIfAbsent(dir.getAbsolutePath(), key -> new Object());
        synchronized (lock) {
            try {
                ensureMirror(dir, cloneUrl, credential, credentialType);
                return diffPaths(dir, cloneUrl, credential, credentialType, base, head);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                        "本地镜像计算差异失败（" + cloneUrl + "）：" + e.getMessage());
            }
        }
    }

    private void ensureMirror(File dir, String cloneUrl, String credential, Integer credentialType)
            throws IOException, GitAPIException {
        if (new File(dir, "HEAD").exists()) {
            try (Git git = Git.open(dir)) {
                FetchCommand fetch = git.fetch().setRemote("origin")
                        .setTimeout(properties.getMirrorTimeoutSeconds());
                CredentialsProvider provider = credentials(credential, credentialType);
                if (provider != null) {
                    fetch.setCredentialsProvider(provider);
                }
                fetch.call();
                LOG.debug("Gitea 本地镜像已更新: {}", dir);
            }
            return;
        }
        Files.createDirectories(dir.toPath());
        LOG.info("Gitea 本地镜像首次克隆: {} -> {}", cloneUrl, dir);
        CloneCommand clone = Git.cloneRepository()
                .setURI(cloneUrl)
                .setDirectory(dir)
                .setBare(true)
                .setMirror(true)
                .setTimeout(properties.getMirrorTimeoutSeconds());
        CredentialsProvider provider = credentials(credential, credentialType);
        if (provider != null) {
            clone.setCredentialsProvider(provider);
        }
        try (Git ignored = clone.call()) {
            LOG.info("Gitea 本地镜像克隆完成: {}", dir);
        }
    }

    private List<String> diffPaths(File dir, String cloneUrl, String credential, Integer credentialType,
                                   String base, String head) throws IOException, GitAPIException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).build()) {
            ObjectId baseId = resolveOrNull(repo, base);
            ObjectId headId = resolveOrNull(repo, head);
            if (baseId == null || headId == null) {
                // 新提交还没同步到本地镜像：拉一次再试（只重试一次，失败就报错）
                fetch(credential, credentialType, repo);
                baseId = resolveOrNull(repo, base);
                headId = resolveOrNull(repo, head);
            }
            if (baseId == null) {
                throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                        "本地镜像里找不到引用 " + base + "（" + cloneUrl + "）");
            }
            if (headId == null) {
                throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                        "本地镜像里找不到引用 " + head + "（" + cloneUrl + "）");
            }
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit baseCommit = walk.parseCommit(baseId);
                RevCommit headCommit = walk.parseCommit(headId);
                RevCommit mergeBase = mergeBase(repo, baseCommit, headCommit);
                try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    formatter.setRepository(repo);
                    formatter.setDetectRenames(true);
                    List<DiffEntry> entries = formatter.scan(mergeBase.getTree(), headCommit.getTree());
                    TreeSet<String> paths = new TreeSet<>();
                    for (DiffEntry entry : entries) {
                        // 删除给旧路径（与 GitHub compare 的 filename 口径一致），其余给新路径
                        String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                                ? entry.getOldPath()
                                : entry.getNewPath();
                        if (path != null && !path.isBlank() && !"/dev/null".equals(path)) {
                            paths.add(path);
                        }
                    }
                    return new ArrayList<>(paths);
                }
            }
        }
    }

    private void fetch(String credential, Integer credentialType, Repository repo)
            throws GitAPIException {
        try (Git git = new Git(repo)) {
            FetchCommand fetch = git.fetch().setRemote("origin")
                    .setTimeout(properties.getMirrorTimeoutSeconds());
            CredentialsProvider provider = credentials(credential, credentialType);
            if (provider != null) {
                fetch.setCredentialsProvider(provider);
            }
            fetch.call();
        }
    }

    /**
     * 解析引用；解析不出来（或对象不在本地）返回 null。
     *
     * <p>两个坑：① JGit 对形如 40 位 sha 的字符串会**先当语法解析成功**、并不校验对象是否存在，
     * 直接走到 parseCommit 才抛 "Missing unknown" —— 所以必须显式查对象库；
     * ② 形如 {@code dev} 的不存在分支会让 resolve 抛异常，统一收敛成 null。
     */
    private static ObjectId resolveOrNull(Repository repo, String ref) {
        try {
            ObjectId id = repo.resolve(ref);
            return (id != null && repo.getObjectDatabase().has(id)) ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** merge-base（三点语义）；无共同祖先（孤儿分支）时退回 base 树，与"全部变更"相比更保守。 */
    private static RevCommit mergeBase(Repository repo, RevCommit base, RevCommit head) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(base.getId()));
            walk.markStart(walk.parseCommit(head.getId()));
            RevCommit mergeBase = walk.next();
            return mergeBase != null ? mergeBase : walk.parseCommit(base.getId());
        }
    }

    /**
     * JGit 的凭据：Gitea 的 Basic 认证<b>先把密码当令牌查</b>，所以令牌放密码位、用户名随意。
     * credentialType=2 时是 user:pass，按第一个冒号拆。
     */
    private static CredentialsProvider credentials(String credential, Integer credentialType) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        if (credentialType != null && credentialType == 2) {
            int idx = credential.indexOf(':');
            String user = idx > 0 ? credential.substring(0, idx) : credential;
            String pass = idx > 0 ? credential.substring(idx + 1) : "";
            return new UsernamePasswordCredentialsProvider(user, pass);
        }
        return new UsernamePasswordCredentialsProvider("oauth2", credential);
    }

    /** 每个 cloneUrl 一个目录：可读后缀 + URL 哈希（避免不同站点同名仓库互相污染）。 */
    private File mirrorDir(String cloneUrl) {
        String hash = sha1(cloneUrl);
        String readable = cloneUrl.replaceAll("[^A-Za-z0-9._-]", "_");
        if (readable.length() > 80) {
            readable = readable.substring(readable.length() - 80);
        }
        return new File(properties.resolvedWorkDir(), hash + "-" + readable);
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算镜像目录名: " + e.getMessage(), e);
        }
    }
}
