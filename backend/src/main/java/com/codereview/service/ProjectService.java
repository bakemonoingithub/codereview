package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.common.ReviewableFiles;
import com.codereview.config.ReviewProperties;
import com.codereview.dto.DeleteImpactResp;
import com.codereview.dto.ProjectCreateReq;
import com.codereview.dto.TreeNodeResp;
import com.codereview.entity.IssueMark;
import com.codereview.entity.Project;
import com.codereview.entity.Report;
import com.codereview.entity.ReportRecord;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.CommitInfo;
import com.codereview.git.CommitPage;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.git.GitTreeEntry;
import com.codereview.mapper.IssueMarkMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.review.ReviewStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final GitHostClient gitHostClient;
    private final ReviewProperties props;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ReportMapper reportMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final IssueMarkMapper issueMarkMapper;

    public ProjectService(ProjectMapper projectMapper, GitHostClient gitHostClient, ReviewProperties props,
                          ReviewRecordMapper reviewRecordMapper, ReportMapper reportMapper,
                          ReportRecordMapper reportRecordMapper, IssueMarkMapper issueMarkMapper) {
        this.projectMapper = projectMapper;
        this.gitHostClient = gitHostClient;
        this.props = props;
        this.reviewRecordMapper = reviewRecordMapper;
        this.reportMapper = reportMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.issueMarkMapper = issueMarkMapper;
    }

    public Project create(ProjectCreateReq req) {
        GitRepoRef ref = GitRepoRef.parse(req.giteaUrl());
        int credentialType = req.credentialType() == null ? 1 : req.credentialType();
        ensureUrlAvailable(req.giteaUrl());
        try {
            gitHostClient.branches(req.credential(), credentialType, ref.owner(), ref.repo());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.GIT_CONNECT_FAILED.getCode(), "仓库连通验证失败: " + e.getMessage());
        }
        Project p = new Project();
        p.setName(req.name());
        p.setGiteaUrl(req.giteaUrl());
        p.setCredential(req.credential());
        p.setCredentialType(credentialType);
        p.setCurrentBranch("main");
        projectMapper.insert(p);
        return p;
    }

    /**
     * 仓库地址在**未删除**的项目中必须唯一。
     *
     * <p>原先由数据库唯一索引 {@code uk_gitea_url} 保证，但唯一索引 + 逻辑删除会让
     * **已删除的项目继续占用该地址** —— 删掉后用同一仓库重建必然失败。V5 移除该索引后，
     * 判重下移到这里（MySQL 不支持"仅对未删除行唯一"的部分唯一索引）。
     *
     * <p>判重依赖 MyBatis-Plus 逻辑删除自动补 {@code is_deleted = 0}：已删除记录不参与，
     * 这正是"删除后可重建"成立的前提。
     *
     * <p>放在连通性验证**之前**：地址已被占用时没必要再打一次远端。
     */
    private void ensureUrlAvailable(String giteaUrl) {
        Long count = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getGiteaUrl, giteaUrl));
        // 判空是为了兼容单测里未 stub 的 mapper（Mockito 默认返回 null）
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "该仓库地址已被其他项目使用：" + giteaUrl);
        }
    }

    public Page<Project> list(long pageNum, long pageSize) {
        return projectMapper.selectPage(new Page<>(pageNum, pageSize), null);
    }

    /**
     * 项目详情。界面用它显示"当前在哪个项目"（面包屑/标题）—— 原先前端只能拉列表去找，
     * 而列表是分页的，翻不到的项目连名字都显示不出来。
     *
     * <p>{@code credential} 是 WRITE_ONLY，不会随响应回传。
     */
    public Project detail(Long projectId) {
        return getOrThrow(projectId);
    }

    /**
     * 删除项目前的**影响范围预览**。
     *
     * <p>前端先调它、再弹确认框：让用户在"知道会一并销毁 N 条审查记录、M 份报告"的
     * 前提下决定。报告是可下载成 Markdown 沉淀的资产，不该在用户不知情时被删掉。
     */
    public DeleteImpactResp deleteImpact(Long projectId) {
        getOrThrow(projectId);
        ReviewRecord blocking = findBlockingRecord(projectId);
        return new DeleteImpactResp(countRecords(projectId), countReports(projectId),
                blocking != null, blocking == null ? null : blockReason(), props.getDeleteBlockMinutes());
    }

    /**
     * 删除项目：逻辑删除项目本身，并**级联**逻辑删除它的审查记录、报告、报告-记录关联与 issue 标记。
     *
     * <p>级联是必要的：这些表用的都是**逻辑外键**（没有物理外键约束），删掉项目后它们会变成
     * "列表里看不到、详情页也进不去"的孤儿数据。其中 {@code issue_mark} 存的是"误报/已采纳"
     * 标记（准确率的数据来源），不清理会留下指向已删记录的孤儿标记。
     *
     * <p>**删除时会再校验一次**"是否有正在进行的审查" —— 确认弹窗打开期间状态可能已经变化，
     * 只在预览时校验是不够的。
     *
     * <p>用的是逻辑删除，数据仍在库中，理论上可恢复，但目前**没有恢复入口**。
     */
    public void delete(Long projectId) {
        getOrThrow(projectId);
        if (findBlockingRecord(projectId) != null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), blockReason());
        }
        // 先收集子记录 id：父行一旦被逻辑删除，后续查询就再也查不到它们了
        List<Long> recordIds = reviewRecordMapper.selectList(
                        new LambdaQueryWrapper<ReviewRecord>().eq(ReviewRecord::getProjectId, projectId))
                .stream().map(ReviewRecord::getId).toList();
        List<Long> reportIds = reportMapper.selectList(
                        new LambdaQueryWrapper<Report>().eq(Report::getProjectId, projectId))
                .stream().map(Report::getId).toList();

        if (!recordIds.isEmpty()) {
            issueMarkMapper.delete(new LambdaQueryWrapper<IssueMark>().in(IssueMark::getRecordId, recordIds));
            reportRecordMapper.delete(
                    new LambdaQueryWrapper<ReportRecord>().in(ReportRecord::getRecordId, recordIds));
        }
        if (!reportIds.isEmpty()) {
            reportRecordMapper.delete(
                    new LambdaQueryWrapper<ReportRecord>().in(ReportRecord::getReportId, reportIds));
        }
        reportMapper.delete(new LambdaQueryWrapper<Report>().eq(Report::getProjectId, projectId));
        reviewRecordMapper.delete(new LambdaQueryWrapper<ReviewRecord>().eq(ReviewRecord::getProjectId, projectId));
        projectMapper.deleteById(projectId);
    }

    private int countRecords(Long projectId) {
        Long c = reviewRecordMapper.selectCount(
                new LambdaQueryWrapper<ReviewRecord>().eq(ReviewRecord::getProjectId, projectId));
        return c == null ? 0 : c.intValue();
    }

    private int countReports(Long projectId) {
        Long c = reportMapper.selectCount(
                new LambdaQueryWrapper<Report>().eq(Report::getProjectId, projectId));
        return c == null ? 0 : c.intValue();
    }

    /**
     * 找一条"正在进行的审查"：状态为**排队中或执行中**，且创建时间在阻塞窗口内。
     *
     * <p>超过窗口的记录**不算**阻塞 —— 视为任务已卡死，否则一个卡死的任务会让项目永远删不掉。
     * 这与验收指标 8 的"1 小时"口径一致。
     *
     * <p>时间基准取 {@code created_at} 而非 {@code started_at}：排队阶段后者仍为 NULL，
     * 用它会拦不住"卡在队列里"的任务。
     */
    private ReviewRecord findBlockingRecord(Long projectId) {
        // SQL 先按状态过滤（省掉无关行的传输）；窗口判定交给下面的纯函数 ——
        // 它是这条规则的唯一权威，且能直接被单测覆盖。
        List<ReviewRecord> candidates = reviewRecordMapper.selectList(
                new LambdaQueryWrapper<ReviewRecord>()
                        .eq(ReviewRecord::getProjectId, projectId)
                        .in(ReviewRecord::getStatus, ReviewStatus.QUEUED, ReviewStatus.RUNNING));
        LocalDateTime now = LocalDateTime.now();
        return candidates.stream()
                .filter(r -> isWithinBlockWindow(r, now, props.getDeleteBlockMinutes()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 该记录是否落在「阻塞删除」的窗口内。
     *
     * <p>规则：状态为**排队中或执行中**，且 **{@code created_at}** 距今不足 {@code windowMinutes} 分钟。
     * 超过窗口的视为任务已卡死，**不再阻止删除** —— 否则一个卡死的任务会让项目永远删不掉。
     * 这与验收指标 8 的"1 小时"口径一致。
     *
     * <p>为什么按 {@code created_at} 而不是 {@code started_at}：排队阶段的 {@code started_at}
     * 仍为 NULL，用它会拦不住"卡在队列里"的任务，与规则意图正好相反。
     *
     * <p>{@code created_at} 为空（历史脏数据）按"不在窗口内"处理：宁可允许删除，
     * 也不让一条脏数据把项目永久锁死。
     *
     * <p>抽成 static 纯函数是为了能直接单测这条规则 —— 它藏在数据库查询里时装不出来，
     * 而它恰恰是本功能最容易写错的地方。
     */
    static boolean isWithinBlockWindow(ReviewRecord record, LocalDateTime now, int windowMinutes) {
        if (record == null || record.getStatus() == null) {
            return false;
        }
        int status = record.getStatus();
        if (status != ReviewStatus.QUEUED && status != ReviewStatus.RUNNING) {
            return false;
        }
        LocalDateTime createdAt = record.getCreatedAt();
        return createdAt != null && createdAt.isAfter(now.minusMinutes(windowMinutes));
    }

    private String blockReason() {
        return "该项目有正在进行的审查（开始不足 " + props.getDeleteBlockMinutes()
                + " 分钟），请等待完成后再删除";
    }

    public List<TreeNodeResp> tree(Long projectId, String branch) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        List<GitTreeEntry> entries = gitHostClient.tree(p.getCredential(), p.getCredentialType(), ref.owner(), ref.repo(), branch);
        return buildTree(entries);
    }

    public List<String> branches(Long projectId) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        return gitHostClient.branches(p.getCredential(), p.getCredentialType(), ref.owner(), ref.repo());
    }

    public List<CommitInfo> commits(Long projectId, String branch) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        return gitHostClient.commits(p.getCredential(), p.getCredentialType(), ref.owner(), ref.repo(), branch);
    }

    public List<String> changedFiles(Long projectId, String base, String head) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        return gitHostClient.changedFiles(p.getCredential(), p.getCredentialType(), ref.owner(), ref.repo(), base, head);
    }

    /** 提交列表（分页）：返回 hasMore 供前端滚动加载。 */
    public CommitPage commitPage(Long projectId, String branch, int page, int pageSize) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        return gitHostClient.commitPage(p.getCredential(), p.getCredentialType(), ref.owner(), ref.repo(),
                branch, page, pageSize);
    }

    /**
     * 单提交详情（列表视图）：剥离每文件 patch，避免大提交首屏传巨量内容；
     * patch 由 {@link #filePatch} 按需单独获取（命中后端缓存，不会重复打远端）。
     */
    public CommitDetail commitDetail(Long projectId, String sha) {
        return loadCommitDetail(projectId, sha).withoutPatches();
    }

    /** 指定文件在该提交中的 patch；无 patch（二进制 / 过大 / 未变更）时返回 null。 */
    public String filePatch(Long projectId, String sha, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return loadCommitDetail(projectId, sha).files().stream()
                .filter(f -> path.equals(f.path()))
                .filter(ChangedFile::hasPatch)
                .map(ChangedFile::patch)
                .findFirst()
                .orElse(null);
    }

    private CommitDetail loadCommitDetail(Long projectId, String sha) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        return gitHostClient.commitDetail(p.getCredential(), p.getCredentialType(), ref.owner(), ref.repo(), sha);
    }

    private Project getOrThrow(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        return p;
    }

    /** 扁平条目 → 嵌套树（按 path 排序保证父节点先于子节点） */
    private List<TreeNodeResp> buildTree(List<GitTreeEntry> entries) {
        List<GitTreeEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(GitTreeEntry::path));
        List<TreeNodeResp> roots = new ArrayList<>();
        Map<String, TreeNodeResp> map = new HashMap<>();
        for (GitTreeEntry e : sorted) {
            String path = e.path();
            String name = path.substring(path.lastIndexOf('/') + 1);
            // reviewable 由后端唯一判定（ReviewableFiles），前端直接读、不自己维护扩展名表
            TreeNodeResp node = new TreeNodeResp(path, name, e.type(),
                    ReviewableFiles.isReviewable(path), new ArrayList<>());
            map.put(path, node);
            int idx = path.lastIndexOf('/');
            String parentPath = idx > 0 ? path.substring(0, idx) : null;
            if (parentPath == null) {
                roots.add(node);
            } else {
                TreeNodeResp parent = map.get(parentPath);
                if (parent != null) {
                    parent.children().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        return roots;
    }
}
