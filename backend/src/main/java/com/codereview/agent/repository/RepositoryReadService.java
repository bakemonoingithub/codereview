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
import com.codereview.git.FixedComparison;
import com.codereview.git.FixedComparisonService;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import com.codereview.git.GitTree;
import com.codereview.git.HostHttpException;
import com.codereview.git.GitTreeEntry;
import com.codereview.git.PullRequests;
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
import java.util.Locale;
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

    private final AgentDatabase db;
    private final GitHostClientRegistry registry;
    private final Cache<String, RepositorySnapshots.FileSnapshot> fileCache;
    private final Cache<String, RepositorySnapshots.PathList> treeCache;

    private final RepositoryProperties properties;
    private final FixedComparisonService comparisons;

    public RepositoryReadService(AgentDatabase db, GitHostClientRegistry registry) {
        this(db, registry, new RepositoryProperties());
    }

    public RepositoryReadService(AgentDatabase db, GitHostClientRegistry registry, RepositoryProperties properties) {
        this(db, registry, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RepositoryReadService(AgentDatabase db, GitHostClientRegistry registry, RepositoryProperties properties,
                                 FixedComparisonService comparisons) {
        this.properties = properties;
        this.comparisons = comparisons;
        this.db = db;
        this.registry = registry;
        this.fileCache = Caffeine.newBuilder()
                .maximumWeight(properties.getFileCacheWeightBytes())
                .weigher((String key, RepositorySnapshots.FileSnapshot snapshot) ->
                        Math.max(1, snapshot.byteSize()))
                .expireAfterWrite(Duration.ofMinutes(properties.getFileCacheMinutes()))
                .build();
        this.treeCache = Caffeine.newBuilder()
                .maximumSize(properties.getTreeCacheSize())
                .expireAfterWrite(Duration.ofMinutes(properties.getTreeCacheMinutes()))
                .build();
    }

    private boolean isReviewable(String path) { return properties.getAllowedExtensions().contains(ReviewableFiles.extensionOf(path)); }

    public RepositorySnapshots.PathList listPaths(String projectId, String sha) {
        ProjectContext context = context(projectId);
        String fixedSha = RepositoryPaths.requireFixedSha(sha);
        String key = context.namespace + "|tree|" + fixedSha;
        return treeCache.get(key, ignored -> {
            GitTree tree = call("读取路径清单",
                    () -> client(context).treeWithMeta(context.credential(), context.credentialType(),
                            context.ref, fixedSha));
            List<RepositorySnapshots.PathEntry> paths = new ArrayList<>();
            for (GitTreeEntry entry : tree.entries()) {
                paths.add(new RepositorySnapshots.PathEntry(entry.path(), entry.type(),
                        "blob".equals(entry.type()) && isReviewable(entry.path())));
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

    /** 分支列表；浏览页用于切换观察版本，真正读取仍使用固定 SHA。 */
    public List<String> branches(String projectId) {
        ProjectContext context = context(projectId);
        return call("读取分支列表", () -> client(context).branches(context.credential(),
                context.credentialType(), context.ref));
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

    /** 公开选材视图与文件树共用运行白名单；不修改共享 Git 缓存中的宿主详情。 */
    public RepositorySnapshots.CommitView commitView(String projectId, String sha) {
        CommitDetail detail = commit(projectId, sha);
        List<RepositorySnapshots.ChangedFileView> files = detail.files().stream()
                .map(file -> new RepositorySnapshots.ChangedFileView(file.path(), file.previousPath(),
                        file.status(), file.additions(), file.deletions(), file.changes(), null,
                        isReviewable(file.path())))
                .toList();
        return new RepositorySnapshots.CommitView(detail.sha(), detail.parents(), detail.message(),
                detail.author(), detail.date(), detail.additions(), detail.deletions(), detail.totalChanges(),
                detail.truncated(), files);
    }

    /**
     * 共享镜像所需的仓库身份与凭据。
     *
     * <p>只给受控的镜像 / 检索组件使用：凭据用于 JGit 拉取，不进入 URL、目录名或诊断正文。
     * {@code namespace} 含项目与项目修订，缓存键用它隔离凭据或仓库地址的变更。
     */
    public MirrorTarget mirrorTarget(String projectId) {
        ProjectContext context = context(projectId);
        return new MirrorTarget(context.project().getId(), context.project().getRepoUrl(),
                context.credential(), context.credentialType(), context.namespace());
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
     * 项目仓库中的 PR 列表。只读取宿主元数据，不克隆镜像，也不把 PR 展开成提交。
     */
    public PullRequestViews.Page pullRequests(String projectId, String state, String targetBranch,
                                              int page, int perPage) {
        ProjectContext context = context(projectId);
        String safeState = normalizePullState(state);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(perPage, 100));
        PullRequests.Page result = call("读取 PR 列表", () -> client(context).pullRequests(
                context.credential(), context.credentialType(), context.ref, safeState,
                blankToNull(targetBranch), safePage, safeSize));
        String provider = client(context).pullRequestProvider();
        List<PullRequestViews.Item> items = result.items().stream()
                .map(item -> toItem(context, item, provider))
                .toList();
        return new PullRequestViews.Page(items, result.hasMore());
    }

    /**
     * PR 详情。同仓库时计算共同祖先；跨仓库、未开放或没有共同祖先时仍返回身份，并用 blocker 说明不能审查的原因。
     */
    public PullRequestViews.Detail pullRequest(String projectId, long number) {
        LoadedPull loaded = loadPull(projectId, number);
        return new PullRequestViews.Detail(loaded.item, loaded.mergeBase, loaded.blocker);
    }

    /** 当前 PR 的净变化清单。版本以这次读取到的源提交和目标提交为准。 */
    public PullRequestViews.Changes pullRequestChanges(String projectId, long number) {
        LoadedPull loaded = loadPull(projectId, number);
        return new PullRequestViews.Changes(loaded.item.number(), loaded.item.sourceHead(),
                loaded.item.targetHead(), loaded.mergeBase, loaded.completeness, loaded.blocker,
                loaded.fileCount, count(loaded.files, "TEXT"), count(loaded.files, "BINARY"),
                count(loaded.files, "UNREADABLE"), loaded.files);
    }

    /**
     * 创建审查前重新读取 PR，并核对用户确认的三个版本。
     * 任一版本变化都拒绝，不把旧确认改写成新提交。
     */
    public PullRequestViews.Binding bindPullRequest(String projectId, long number, String sourceHead,
                                                    String targetHead, String mergeBase) {
        String observedSource = RepositoryPaths.requireFixedSha(sourceHead);
        String observedTarget = RepositoryPaths.requireFixedSha(targetHead);
        String observedBase = RepositoryPaths.requireFixedSha(mergeBase);
        LoadedPull loaded = loadPull(projectId, number);
        if (loaded.blocker != null) {
            throw pullBlocker(loaded);
        }
        if (!observedSource.equals(loaded.item.sourceHead())
                || !observedTarget.equals(loaded.item.targetHead())
                || !observedBase.equals(loaded.mergeBase)) {
            throw new ApiException(ErrorCode.SOURCE_MOVED,
                    "PR #" + number + " 的源提交、目标提交或共同祖先已变化，请刷新范围后重新提交",
                    Map.of("pullNumber", number,
                            "observedSourceHead", observedSource,
                            "currentSourceHead", loaded.item.sourceHead() == null ? "UNKNOWN" : loaded.item.sourceHead(),
                            "observedTargetHead", observedTarget,
                            "currentTargetHead", loaded.item.targetHead() == null ? "UNKNOWN" : loaded.item.targetHead(),
                            "observedMergeBase", observedBase,
                            "currentMergeBase", loaded.mergeBase == null ? "UNKNOWN" : loaded.mergeBase));
        }
        return new PullRequestViews.Binding(loaded.item, loaded.mergeBase, loaded.completeness, loaded.files,
                count(loaded.files, "TEXT"), count(loaded.files, "BINARY"), count(loaded.files, "UNREADABLE"));
    }

    /**
     * 两个已经冻结的提交之间的单文件差异。{@code baseSha} 是比较基线本身，不再改取第一父提交。
     */
    public RepositorySnapshots.DiffSides diffRange(String projectId, String baseSha, String headSha, String path) {
        ProjectContext context = context(projectId);
        String base = RepositoryPaths.requireFixedSha(baseSha);
        String head = RepositoryPaths.requireFixedSha(headSha);
        String safePath = RepositoryPaths.requireSafePath(path);
        FixedComparison.Result comparison = frozenComparison(context, base, head);
        if (!comparison.complete()) {
            throw new ApiException(ErrorCode.SNAPSHOT_INCOMPLETE,
                    "PR 净变化清单超过上限，不能读取其中的单个文件");
        }
        FixedComparison.FileChange file = findChange(comparison, safePath);
        if (file == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "该净变化没有此文件：" + safePath);
        }
        if (!file.text()) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE,
                    "该净变化文件不可作为文本审查材料：" + file.path(),
                    Map.of("reason", file.reason() == null ? file.category() : file.reason(),
                            "category", file.category()));
        }
        boolean added = ChangedFile.ADDED.equals(file.status());
        boolean removed = ChangedFile.REMOVED.equals(file.status());
        String beforePath = file.oldPath() != null ? file.oldPath() : file.path();
        RepositorySnapshots.Side after = removed
                ? RepositorySnapshots.Side.missing("REMOVED")
                : readSide(projectId, head, file.path());
        RepositorySnapshots.Side before = added
                ? RepositorySnapshots.Side.missing("ADDED")
                : readSide(projectId, base, beforePath);
        return new RepositorySnapshots.DiffSides(head, base, file.path(), file.oldPath(), file.status(),
                null, before, after);
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
        if (bytes > properties.getMaxFileBytes()) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE,
                    "文件超过目标读取上限 " + (properties.getMaxFileBytes() / 1024 / 1024) + " MiB：" + path,
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

    private LoadedPull loadPull(String projectId, long number) {
        if (number <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "PR 编号必须是正整数",
                    Map.of("pullNumber", number));
        }
        ProjectContext context = context(projectId);
        PullRequests.Item raw = call("读取 PR", () -> client(context).pullRequest(
                context.credential(), context.credentialType(), context.ref, number));
        PullRequestViews.Item item = toItem(context, raw, client(context).pullRequestProvider());
        if (!item.sameRepository()) {
            return LoadedPull.blocked(item, PullRequestViews.CROSS_REPOSITORY);
        }
        if (!openPull(item)) {
            LoadedPull closed = compareSameRepository(context, item);
            return new LoadedPull(item, closed.mergeBase, closed.completeness, closed.fileCount,
                    closed.files, PullRequestViews.PR_NOT_OPEN);
        }
        return compareSameRepository(context, item);
    }

    private LoadedPull compareSameRepository(ProjectContext context, PullRequestViews.Item item) {
        if (item.sourceHead() == null || item.targetHead() == null) {
            return LoadedPull.blocked(item, PullRequestViews.NO_MERGE_BASE);
        }
        try {
            FixedComparison.Result comparison = liveComparison(context, item.targetHead(), item.sourceHead());
            List<PullRequestViews.FileChange> files = comparison.files().stream()
                    .map(file -> new PullRequestViews.FileChange(file.path(), file.oldPath(), file.status(),
                            file.category(), file.reason()))
                    .toList();
            String blocker = comparison.complete() ? null : PullRequestViews.TRUNCATED;
            return new LoadedPull(item, comparison.mergeBase(), comparison.completeness(),
                    comparison.fileCount(), files, blocker);
        } catch (ApiException e) {
            if (e.details() instanceof Map<?, ?> details
                    && PullRequestViews.NO_MERGE_BASE.equals(details.get("reason"))) {
                return LoadedPull.blocked(item, PullRequestViews.NO_MERGE_BASE);
            }
            throw e;
        }
    }

    private FixedComparison.Result liveComparison(ProjectContext context, String targetHead, String sourceHead) {
        return compare(context, targetHead, sourceHead, true);
    }

    private FixedComparison.Result frozenComparison(ProjectContext context, String mergeBase, String sourceHead) {
        return compare(context, mergeBase, sourceHead, false);
    }

    private FixedComparison.Result compare(ProjectContext context, String base, String head, boolean findMergeBase) {
        GitHostClient host = client(context);
        if (comparisons == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "PR 比较组件未装配");
        }
        if (!host.supportsLocalComparison()) {
            throw new ApiException(ErrorCode.REPO_UNREACHABLE,
                    "本地镜像比较已关闭，无法计算 PR 净变化");
        }
        String cloneUrl = call("确定镜像地址", () -> host.mirrorCloneUrl(context.ref));
        try {
            return findMergeBase
                    ? comparisons.between(cloneUrl, context.credential(), context.credentialType(),
                    base, head, properties.getMaxFileBytes())
                    : comparisons.trees(cloneUrl, context.credential(), context.credentialType(),
                    base, head, properties.getMaxFileBytes());
        } catch (BusinessException e) {
            throw mapComparison(e);
        }
    }

    private static ApiException mapComparison(BusinessException failure) {
        String message = failure.getMessage() == null ? "计算 PR 净变化失败" : failure.getMessage();
        if (message.contains("没有共同祖先")) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                    Map.of("reason", PullRequestViews.NO_MERGE_BASE));
        }
        return new ApiException(ErrorCode.REPO_UNREACHABLE, "计算 PR 净变化失败：" + message);
    }

    private static ApiException pullBlocker(LoadedPull loaded) {
        return switch (loaded.blocker) {
            case PullRequestViews.CROSS_REPOSITORY -> new ApiException(ErrorCode.VALIDATION_FAILED,
                    "首版只审查已登记项目仓库内的 PR，不接受 fork 或其他仓库",
                    Map.of("reason", PullRequestViews.CROSS_REPOSITORY, "pullNumber", loaded.item.number()));
            case PullRequestViews.PR_NOT_OPEN -> new ApiException(ErrorCode.VALIDATION_FAILED,
                    "只能审查未合并且仍开放的 PR",
                    Map.of("reason", PullRequestViews.PR_NOT_OPEN, "pullNumber", loaded.item.number(),
                            "state", loaded.item.state() == null ? "" : loaded.item.state()));
            case PullRequestViews.NO_MERGE_BASE -> new ApiException(ErrorCode.VALIDATION_FAILED,
                    "PR 的源提交和目标提交没有共同祖先",
                    Map.of("reason", PullRequestViews.NO_MERGE_BASE, "pullNumber", loaded.item.number()));
            case PullRequestViews.TRUNCATED -> new ApiException(ErrorCode.SNAPSHOT_INCOMPLETE,
                    "PR 净变化超过 " + FixedComparison.MAX_FILES + " 个文件，不能把部分清单当作完整范围",
                    Map.of("reason", PullRequestViews.TRUNCATED, "pullNumber", loaded.item.number(),
                            "fileCount", loaded.fileCount));
            default -> new ApiException(ErrorCode.VALIDATION_FAILED, "该 PR 不能发起审查",
                    Map.of("reason", loaded.blocker));
        };
    }

    private PullRequestViews.Item toItem(ProjectContext context, PullRequests.Item item, String provider) {
        String projectRepository = context.ref.owner() + "/" + context.ref.repo();
        boolean same = projectRepository.equalsIgnoreCase(nullToEmpty(item.sourceRepository()))
                && projectRepository.equalsIgnoreCase(nullToEmpty(item.targetRepository()));
        return new PullRequestViews.Item(item.number(), item.title(), item.state(), item.draft(),
                item.merged(), item.author(), item.sourceBranch(), item.targetBranch(), item.sourceSha(),
                item.targetSha(), item.sourceRepository(), item.targetRepository(), same, item.url(),
                item.updatedAt(), provider);
    }

    private static FixedComparison.FileChange findChange(FixedComparison.Result comparison, String path) {
        for (FixedComparison.FileChange file : comparison.files()) {
            if (path.equals(file.path()) || path.equals(file.oldPath())) {
                return file;
            }
        }
        return null;
    }

    private static int count(List<PullRequestViews.FileChange> files, String category) {
        int total = 0;
        for (PullRequestViews.FileChange file : files) {
            if (category.equals(file.category())) {
                total++;
            }
        }
        return total;
    }

    private static boolean openPull(PullRequestViews.Item item) {
        return "open".equalsIgnoreCase(item.state()) && !item.merged();
    }

    private static String normalizePullState(String state) {
        String value = state == null || state.isBlank() ? "open" : state.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("open") && !value.equals("closed") && !value.equals("all")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "PR 状态只能是 open、closed 或 all",
                    Map.of("state", state));
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record LoadedPull(PullRequestViews.Item item, String mergeBase, String completeness,
                              int fileCount, List<PullRequestViews.FileChange> files, String blocker) {
        private static LoadedPull blocked(PullRequestViews.Item item, String blocker) {
            return new LoadedPull(item, null, null, 0, List.of(), blocker);
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

    /** 共享镜像的读取目标；凭据只在内存中使用。 */
    public record MirrorTarget(String projectId, String cloneUrl, String credential,
                               Integer credentialType, String namespace) {
    }
}
