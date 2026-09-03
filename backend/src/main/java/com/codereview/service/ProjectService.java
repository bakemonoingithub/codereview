package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.ProjectCreateReq;
import com.codereview.dto.TreeNodeResp;
import com.codereview.entity.Project;
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
        // 保存前验证仓库连通（拉分支列表）
        GitRepoRef ref = GitRepoRef.parse(req.giteaUrl());
        try {
            gitHostClient.branches(req.credential(), ref.owner(), ref.repo());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.GIT_CONNECT_FAILED.getCode(), "仓库连通验证失败: " + e.getMessage());
        }
        Project p = new Project();
        p.setName(req.name());
        p.setGiteaUrl(req.giteaUrl());
        p.setCredential(req.credential());
        p.setCredentialType(req.credentialType() == null ? 1 : req.credentialType());
        p.setCurrentBranch("main");
        projectMapper.insert(p);
        return p;
    }

    public Page<Project> list(long pageNum, long pageSize) {
        return projectMapper.selectPage(new Page<>(pageNum, pageSize), null);
    }

    public List<TreeNodeResp> tree(Long projectId, String branch) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        List<GitTreeEntry> entries = gitHostClient.tree(p.getCredential(), ref.owner(), ref.repo(), branch);
        return buildTree(entries);
    }

    public List<String> branches(Long projectId) {
        Project p = getOrThrow(projectId);
        GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
        return gitHostClient.branches(p.getCredential(), ref.owner(), ref.repo());
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
