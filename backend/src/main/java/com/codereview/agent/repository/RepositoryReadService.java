package com.codereview.agent.repository;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.common.ReviewableFiles;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import com.codereview.git.GitTreeEntry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 固定版本读取：路径清单、文件正文、行范围、提交差异的两侧。
 *
 * <p>与旧实现的分界：
 * <ul>
 *   <li>只接受完整 40 位 SHA，不把分支名当固定版本；</li>
 *   <li>缓存键包含项目、项目修订、SHA 与路径，令牌替换后旧内容自然失联，不做"按分支名缓存"；</li>
 *   <li>请求过的内容原样返回（最多 4 MiB），完整性、哈希与行数一起给出；</li>
 *   <li>这里只做读取与分类，不做传输重试 —— 重试由宿主客户端与 P30.5 的统一调用负责，
 *       避免同一请求在多层各重试一遍放大额度消耗。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class RepositoryReadService {

    /** 目标文件读取上限；超过即失败，不静默截断后当作完整内容。 */
    static final int MAX_TARGET_BYTES = 4 * 1024 * 1024;
    private static final long CACHE_MAX_WEIGHT_BYTES = 128L * 1024 * 1024;

    private final AgentDatabase db;
    private final GitHostClientRegistry registry;
    private final Cache<String, RepositorySnapshots.FileSnapshot> fileCache;
    private final Cache<String, RepositorySnapshots.PathList> treeCache;

    public RepositoryReadService(AgentDatabase db, GitHostClientRegistry registry) {
        this.db = db;
        this.registry = registry;
        this.fileCache = Caffeine.newBuilder()
                .maximumWeight(CACHE_MAX_WEIGHT_BYTES)
                .weigher((String key, RepositorySnapshots.FileSnapshot snapshot) ->
                        Math.max(1, snapshot.byteSize()))
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
        this.treeCache = Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    public RepositorySnapshots.PathList listPaths(String projectId, String sha) {
        ProjectContext context = context(projectId);
        String fixedSha = RepositoryPaths.requireFixedSha(sha);
        String key = context.namespace + "|tree|" + fixedSha;
        return treeCache.get(key, ignored -> {
            List<GitTreeEntry> entries = call("读取路径清单",
                    () -> client(context).tree(context.credential(), context.credentialType(),
                            context.ref, fixedSha));
            List<RepositorySnapshots.PathEntry> paths = new ArrayList<>();
            for (GitTreeEntry entry : entries) {
                paths.add(new RepositorySnapshots.PathEntry(entry.path(), entry.type(),
                        "blob".equals(entry.type()) && ReviewableFiles.isReviewable(entry.path())));
            }
            paths.sort(Comparator.comparing(RepositorySnapshots.PathEntry::path));
            return new RepositorySnapshots.PathList(paths, fixedSha, false);
        });
    }

    public RepositorySnapshots.FileSnapshot readFile(String projectId, String sha, String path) {
        ProjectContext context = context(projectId);
        String fixedSha = RepositoryPaths.requireFixedSha(sha);
        String safePath = RepositoryPaths.requireSafePath(path);
        return fileCache.get(context.namespace + "|" + fixedSha + "|" + safePath,
                ignored -> loadFile(context, fixedSha, safePath));
    }

    public RepositorySnapshots.RangeSnapshot readRange(String projectId, String sha, String path,
                                                       int startLine, int endLine) {
        if (startLine < 1 || endLine < startLine) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "行范围必须从 1 开始且结束不小于开始",
                    Map.of("startLine", startLine, "endLine", endLine));
        }
        RepositorySnapshots.FileSnapshot file = readFile(projectId, sha, path);
        String[] lines = splitLines(file.content());
        int from = Math.min(startLine, lines.length + 1);
        int to = Math.min(endLine, lines.length);
        StringBuilder text = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(lines[i - 1]);
        }
        boolean hasMore = to < lines.length;
        return new RepositorySnapshots.RangeSnapshot(file.sha(), file.path(), startLine, to,
                text.toString(), hasMore);
    }

    /**
     * 提交差异的两侧。删除侧取第一父提交，新增/删除文件缺失的一侧明确标记，
     * 不拿当前分支或另一侧顶替。
     */
    public RepositorySnapshots.DiffSides diff(String projectId, String headSha, String path) {
        ProjectContext context = context(projectId);
        String head = RepositoryPaths.requireFixedSha(headSha);
        String safePath = RepositoryPaths.requireSafePath(path);
        CommitDetail detail = call("读取提交差异",
                () -> client(context).commitDetail(context.credential(), context.credentialType(),
                        context.ref, head));
        ChangedFile file = findChanged(detail, safePath);
        if (file == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "该提交没有变更此文件：" + safePath);
        }

        String note = null;
        boolean binary = false;
        String after = null;
        if (!file.removed()) {
            try {
                after = readFile(projectId, head, safePath).content();
            } catch (ApiException e) {
                if (e.errorCode() != ErrorCode.FILE_NOT_READABLE) {
                    throw e;
                }
                binary = true;
                note = e.getMessage();
            }
        }

        String base = detail.baseSha();
        String beforePath = file.previousPath() != null && !file.previousPath().isBlank()
                ? file.previousPath() : safePath;
        String before = null;
        boolean beforeMissing = base == null && !file.added();
        if (base != null && !file.added()) {
            try {
                before = readFile(projectId, base, beforePath).content();
            } catch (ApiException e) {
                beforeMissing = true;
                if (note == null) {
                    note = e.getMessage();
                }
            }
        }
        return new RepositorySnapshots.DiffSides(head, base, safePath, file.previousPath(), file.status(),
                before, after, file.patch(), beforeMissing, file.removed(), binary, note);
    }

    private RepositorySnapshots.FileSnapshot loadFile(ProjectContext context, String sha, String path) {
        String content = call("读取文件", () -> client(context).rawFile(context.credential(),
                context.credentialType(), context.ref, sha, path));
        if (content == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "该版本没有文件：" + path);
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (content.indexOf('\u0000') >= 0) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE, "二进制文件不可作为审查材料：" + path);
        }
        if (bytes > MAX_TARGET_BYTES) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE,
                    "文件超过目标读取上限 " + (MAX_TARGET_BYTES / 1024 / 1024) + " MiB：" + path,
                    Map.of("byteSize", bytes));
        }
        return new RepositorySnapshots.FileSnapshot(sha, path, content, sha256(content), bytes,
                countLines(content));
    }

    private ProjectContext context(String projectId) {
        AgentProject project = db.mapper(AgentProjectMapper.class)
                .selectById(AgentIds.requireValid(projectId, "项目 ID"));
        if (project == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在：" + projectId);
        }
        GitRepoRef ref;
        try {
            ref = GitRepoRef.parse(project.getRepoUrl());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.REPO_UNREACHABLE, "项目仓库地址无法解析",
                    Map.of("projectId", projectId));
        }
        String namespace = "p|" + project.getId() + "|r" + project.getRevision();
        return new ProjectContext(project, ref, namespace);
    }

    private GitHostClient client(ProjectContext context) {
        try {
            return registry.forRepo(context.ref);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.REPO_UNREACHABLE, "未接入的仓库宿主：" + context.ref.host());
        }
    }

    private <T> T call(String action, Supplier<T> work) {
        try {
            return work.get();
        } catch (ApiException e) {
            throw e;
        } catch (BusinessException e) {
            if (e.getCode() == ResultCode.GIT_FILE_NOT_VIEWABLE.getCode()) {
                throw new ApiException(ErrorCode.FILE_NOT_READABLE, e.getMessage());
            }
            throw new ApiException(ErrorCode.REPO_UNREACHABLE, action + "失败：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.REPO_UNREACHABLE,
                    action + "失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static ChangedFile findChanged(CommitDetail detail, String path) {
        if (detail.files() == null) {
            return null;
        }
        for (ChangedFile file : detail.files()) {
            if (path.equals(file.path()) || path.equals(file.previousPath())) {
                return file;
            }
        }
        return null;
    }

    private static String[] splitLines(String content) {
        if (content.isEmpty()) {
            return new String[0];
        }
        String normalized = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        return normalized.split("\n", -1);
    }

    private static int countLines(String content) {
        return splitLines(content).length;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private record ProjectContext(AgentProject project, GitRepoRef ref, String namespace) {
        String credential() {
            return project.getCredentialRef();
        }

        int credentialType() {
            return project.getCredentialType() == null ? 1 : project.getCredentialType();
        }
    }
}
