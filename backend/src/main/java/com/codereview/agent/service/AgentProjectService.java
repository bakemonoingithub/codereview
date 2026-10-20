package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ProjectDtos;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 项目 v2 服务。
 *
 * <p>三件事必须和新设计一致：仓库地址锁定（只在新建时可写）、令牌留空保留且新值先验证再替换、
 * 更新用 revision 条件防止两个页面互相覆盖。凭据只写不读，返回值只有"是否已配置"。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentProjectService {

    private final AgentDatabase db;
    private final GitHostClientRegistry gitHostClients;

    public AgentProjectService(AgentDatabase db, GitHostClientRegistry gitHostClients) {
        this.db = db;
        this.gitHostClients = gitHostClients;
    }

    public PageResponse<ProjectDtos.View> list(String q, String cursor, Integer limit) {
        int size = AgentPaging.limit(limit);
        LambdaQueryWrapper<AgentProject> wrapper = new LambdaQueryWrapper<>();
        if (q != null && !q.isBlank()) {
            wrapper.like(AgentProject::getName, q.trim());
        }
        AgentPaging.applyCursor(wrapper, cursor, AgentProject::getCreatedAt, AgentProject::getId);
        AgentPaging.applyOrder(wrapper, AgentProject::getCreatedAt, AgentProject::getId);
        wrapper.last("limit " + (size + 1));
        PageResponse<AgentProject> page = AgentPaging.assemble(
                db.mapper(AgentProjectMapper.class).selectList(wrapper), size,
                AgentProject::getCreatedAt, AgentProject::getId);
        return new PageResponse<>(page.items().stream().map(this::toView).toList(),
                page.nextCursor(), page.hasMore());
    }

    public ProjectDtos.View create(ProjectDtos.Create req) {
        GitRepoRef ref = parseRepo(req.repoUrl());
        int credentialType = req.credentialType() == null ? 1 : req.credentialType();
        if (countByRepoUrl(req.repoUrl()) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "该仓库地址已被其他项目使用：" + req.repoUrl());
        }
        verifyRepo(ref, req.credential(), credentialType);

        AgentProject project = new AgentProject();
        project.setId(AgentIds.next());
        project.setName(req.name().trim());
        project.setRepoUrl(req.repoUrl().trim());
        project.setCredentialRef(blankToNull(req.credential()));
        project.setCredentialType(credentialType);
        project.setCurrentBranch(req.currentBranch() == null || req.currentBranch().isBlank()
                ? "main" : req.currentBranch().trim());
        project.setRevision(0);
        project.setCreatedAt(AgentTime.now());
        project.setUpdatedAt(AgentTime.now());
        project.setIsDeleted(0);
        db.mapper(AgentProjectMapper.class).insert(project);
        return toView(project);
    }

    public ProjectDtos.View update(String id, ProjectDtos.Update req) {
        AgentProject current = require(id);
        requireRevision(current.getRevision(), req.expectedRevision());

        String newCredential = blankToNull(req.credential());
        if (newCredential != null) {
            // 先验证新令牌，再替换；失败时原值保持不变。
            verifyRepo(parseRepo(current.getRepoUrl()), newCredential, current.getCredentialType());
        }

        AgentProject patch = new AgentProject();
        patch.setName(req.name().trim());
        patch.setUpdatedAt(AgentTime.now());
        patch.setRevision(req.expectedRevision() + 1);
        if (newCredential != null) {
            patch.setCredentialRef(newCredential);
        }
        int affected = db.mapper(AgentProjectMapper.class).update(patch,
                new LambdaUpdateWrapper<AgentProject>()
                        .eq(AgentProject::getId, id)
                        .eq(AgentProject::getRevision, req.expectedRevision()));
        if (affected == 0) {
            throw new ApiException(ErrorCode.REVISION_CONFLICT, "项目已被其他操作修改，请刷新后重试",
                    Map.of("currentRevision", require(id).getRevision()));
        }
        return toView(require(id));
    }

    public ProjectDtos.View detail(String id) {
        return toView(require(id));
    }

    private AgentProject require(String id) {
        AgentProject project = db.mapper(AgentProjectMapper.class).selectById(AgentIds.requireValid(id, "项目 ID"));
        if (project == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在：" + id);
        }
        return project;
    }

    private long countByRepoUrl(String repoUrl) {
        Long count = db.mapper(AgentProjectMapper.class).selectCount(
                new LambdaQueryWrapper<AgentProject>().eq(AgentProject::getRepoUrl, repoUrl.trim()));
        return count == null ? 0 : count;
    }

    private static void requireRevision(int current, Integer expected) {
        if (expected == null || current != expected) {
            throw new ApiException(ErrorCode.REVISION_CONFLICT, "项目已被其他操作修改，请刷新后重试",
                    Map.of("currentRevision", current));
        }
    }

    private void verifyRepo(GitRepoRef ref, String credential, int credentialType) {
        try {
            gitHostClients.forRepo(ref).branches(credential, credentialType, ref);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.REPO_UNREACHABLE, "仓库连通验证失败：" + safeMessage(e));
        }
    }

    private static GitRepoRef parseRepo(String url) {
        try {
            return GitRepoRef.parse(url);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "仓库地址无法解析：" + url,
                    Map.of("field", "repoUrl"));
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProjectDtos.View toView(AgentProject project) {
        return new ProjectDtos.View(project.getId(), project.getName(), project.getRepoUrl(),
                project.getCurrentBranch(),
                project.getCredentialRef() != null && !project.getCredentialRef().isBlank(),
                project.getRevision(), project.getCreatedAt(), project.getUpdatedAt());
    }
}
