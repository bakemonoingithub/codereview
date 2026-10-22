package com.codereview.git;

import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Gitea 1.16.1 没有 compare 接口，两个引用之间的变更文件只能自己算。
 *
 * <p>做法：每个仓库在本地留一份 <b>bare mirror</b>（{@code git clone --mirror} 的等价物），
 * 按需 fetch，然后用 <b>merge-base 三点语义</b> {@code git diff base...head} 算变更文件 ——
 * 与 GitHub compare 的语义一致（直接拿 base 树与 head 树比会多出 base 侧独有的变更）。
 *
 * <p>镜像的克隆、拉取与锁已提炼到 {@link GitMirror}，由 Agent 只读检索共用同一份本地镜像；
 * 本类只保留「在镜像里算三点差异」这件事。拉取失败/引用解析不出来一律<b>显式报错</b>，
 * 绝不返回空列表冒充"没有变更"。
 */
@Component
public class GiteaGitMirror {

    private static final Logger LOG = LoggerFactory.getLogger(GiteaGitMirror.class);

    private final GitMirror mirror;

    public GiteaGitMirror(GiteaProperties properties) {
        this(properties, new GitMirror(properties));
    }

    /** 生产装配使用共享的镜像组件；测试仍可用单参构造自建。 */
    @org.springframework.beans.factory.annotation.Autowired
    public GiteaGitMirror(GiteaProperties properties, GitMirror mirror) {
        this.mirror = mirror;
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
        List<String> required = new ArrayList<>();
        required.add(base);
        required.add(head);
        return mirror.using(cloneUrl, credential, credentialType, required, null,
                dir -> diffPaths(dir, cloneUrl, base, head));
    }

    private List<String> diffPaths(File dir, String cloneUrl, String base, String head)
            throws IOException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).build()) {
            ObjectId baseId = resolveOrNull(repo, base);
            ObjectId headId = resolveOrNull(repo, head);
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
                    LOG.debug("Gitea 本地镜像差异完成: {} ({} 项)", dir.getName(), paths.size());
                    return new ArrayList<>(paths);
                }
            }
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
}
