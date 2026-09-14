package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.ProjectCreateReq;
import com.codereview.dto.TreeNodeResp;
import com.codereview.entity.Project;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.CommitInfo;
import com.codereview.git.CommitPage;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.git.GitTreeEntry;
import com.codereview.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final GitHostClient gitHostClient;

    public ProjectService(ProjectMapper projectMapper, GitHostClient gitHostClient) {
        this.projectMapper = projectMapper;
        this.gitHostClient = gitHostClient;
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
            TreeNodeResp node = new TreeNodeResp(path, name, e.type(), new ArrayList<>());
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
