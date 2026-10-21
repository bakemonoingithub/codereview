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
import com.codereview.git.GitTree;
import com.codereview.git.HostHttpException;
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

    public RepositorySnapshots.PathList listPaths(String projectId, String sha) {        ProjectContext context = context(projectId);
        String fixedSha = RepositoryPaths.requireFixedSha(sha);
        String key = context.namespace + "|tree|" + fixedSha;
        return treeCache.get(key, ignored -> {
            GitTree tree = call("读取路径清单",
                    () -> client(context).treeWithMeta(context.credential(), context.credentialType(),
                            context.ref, fixedSha));
            List<RepositorySnapshots.PathEntry> paths = new ArrayList<>();
            for (GitTreeEntry entry : tree.entries()) {
                paths.add(new RepositorySnapshots.PathEntry(entry.path(), entry.type(),
                        "blob".equals(entry.type()) && ReviewableFiles.isReviewable(entry.path())));
            }
            paths.sort(Comparator.comparing(RepositorySnapshots.PathEntry::path));
            return new RepositorySnapshots.PathList(paths, fixedSha, tree.truncated());
        });
    }

    /**
     * 分支当前 HEAD。只用于发起任务时确认观察到的版本是否移动；读取材料仍必须使用固定 SHA。
     */
    public String resolveBranchHead(String projectId, String branch) {
        if (branch == null || branch.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少分支名");
        }
        ProjectContext context = context(projectId);
        return call("解析分支 HEAD", () -> client(context).headCommitSha(context.credential(),
                context.credentialType(), context.ref, branch.trim()));
    }

    /** 提交列表分页；只用于发起表单选择固定提交，不改变任务读取的 SHA 语义。 */
    public com.codereview.git.CommitPage commits(String projectId, String branch, int page,
                                                 int perPage) {
        if (branch == null || branch.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少分支名");
        }
        ProjectContext context = context(projectId);
        return call("读取提交列表", () -> client(context).commitPage(context.credential(),
                context.credentialType(), context.ref, branch.trim(), Math.max(1, page),
                Math.max(1, Math.min(perPage, 100))));
    }

    /** 单提交详情；返回时去掉 patch，差异正文仍按路径走 {@link #diff}。 */
    public CommitDetail commit(String projectId, String sha) {
        ProjectContext context = context(projectId);
        String fixedSha = RepositoryPaths.requireFixedSha(sha);
        return call("读取提交详情",
                () -> client(context).commitDetail(context.credential(), context.credentialType(),
                        context.ref, fixedSha)).withoutPatches();
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
            if (i > from) {
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
            if (detail.truncated()) {
                throw new ApiException(ErrorCode.SNAPSHOT_INCOMPLETE,
                        "宿主返回的提交文件清单不完整，无法判断该文件是否变更：" + safePath);
            }
            throw new ApiException(ErrorCode.NOT_FOUND, "该提交没有变更此文件：" + safePath);
        }

        String canonicalPath = file.path();
        String base = detail.baseSha();
        String beforePath = file.previousPath() != null && !file.previousPath().isBlank()
                ? file.previousPath() : canonicalPath;

        RepositorySnapshots.Side after = file.removed()
                ? RepositorySnapshots.Side.missing("REMOVED")
                : readSide(projectId, head, canonicalPath);
        RepositorySnapshots.Side before;
        if (file.added()) {
            before = RepositorySnapshots.Side.missing("ADDED");
        } else if (base == null) {
            before = RepositorySnapshots.Side.missing("ROOT_COMMIT");
        } else {
            before = readSide(projectId, base, beforePath);
        }
        return new RepositorySnapshots.DiffSides(head, base, canonicalPath, file.previousPath(),
                file.status(), file.patch(), before, after);
    }

    /**
     * 读取差异的一侧：把"不存在"与"存在但读不到"分开。
     *
     * <p>任一侧的宿主读取失败都不影响另一侧，保留具体错误码作为不可读原因；
     * 参数校验等程序入口错误仍由接口返回失败。
     */
    private RepositorySnapshots.Side readSide(String projectId, String sha, String path) {
        try {
            return RepositorySnapshots.Side.present(readFile(projectId, sha, path).content());
        } catch (ApiException e) {
            if (e.errorCode() == ErrorCode.NOT_FOUND) {
                return RepositorySnapshots.Side.missing("NOT_AVAILABLE");
            }
            if (e.errorCode() == ErrorCode.FILE_NOT_READABLE) {
                return RepositorySnapshots.Side.unreadable(reasonOf(e));
            }
            return switch (e.errorCode()) {
                case UPSTREAM_TIMEOUT, REPO_UNAUTHORIZED, RATE_LIMITED, REPO_UNAVAILABLE, REPO_UNREACHABLE ->
                        RepositorySnapshots.Side.unreadable(e.errorCode().code());
                default -> throw e;
            };
        }
    }

    private static String reasonOf(ApiException e) {
        if (e.details() instanceof Map<?, ?> details && details.get("reason") instanceof String reason) {
            return reason;
        }
        return "READ_FAILED";
    }

    private RepositorySnapshots.FileSnapshot loadFile(ProjectContext context, String sha, String path) {
        String content = call("读取文件", () -> client(context).rawFile(context.credential(),
                context.credentialType(), context.ref, sha, path));
        if (content == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "该版本没有文件：" + path);
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (content.indexOf('\u0000') >= 0) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE, "二进制文件不可作为审查材料：" + path,
                    Map.of("reason", "BINARY"));
        }
        if (bytes > MAX_TARGET_BYTES) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE,
                    "文件超过目标读取上限 " + (MAX_TARGET_BYTES / 1024 / 1024) + " MiB：" + path,
                    Map.of("reason", "TOO_LARGE", "byteSize", bytes));
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
        } catch (HostHttpException e) {
            throw mapHostFailure(action, e);
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

    /**
     * 把宿主错误还原成各自的语义：404 是"确实没有"，401/403 是凭据问题，
     * 429 可等待重试，5xx 是宿主暂时不可用；超时不伪造 HTTP 状态。
     */
    private static ApiException mapHostFailure(String action, HostHttpException e) {
        if (e.timeout()) {
            return new ApiException(ErrorCode.UPSTREAM_TIMEOUT, action + "超时或连接失败");
        }
        int status = e.statusCode();
        if (status == 404) {
            return new ApiException(ErrorCode.NOT_FOUND, action + "：资源不存在或无权访问",
                    Map.of("status", status));
        }
        if (status == 401 || status == 403) {
            return new ApiException(ErrorCode.REPO_UNAUTHORIZED, action + "：仓库凭据被拒绝",
                    Map.of("status", status));
        }
        if (status == 429) {
            return new ApiException(ErrorCode.RATE_LIMITED, action + "：宿主限流",
                    e.retryAfter() == null ? Map.of("status", status)
                            : Map.of("status", status, "retryAfter", e.retryAfter()));
        }
        if (status >= 500) {
            return new ApiException(ErrorCode.REPO_UNAVAILABLE, action + "：宿主暂时不可用",
                    Map.of("status", status));
        }
        return new ApiException(ErrorCode.REPO_UNREACHABLE, action + "失败：HTTP " + status,
                Map.of("status", status));
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
            return project.getCredential();
        }

        int credentialType() {
            return project.getCredentialType() == null ? 1 : project.getCredentialType();
        }
    }
}
