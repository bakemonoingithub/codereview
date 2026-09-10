package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.dto.ProjectCreateReq;
import com.codereview.dto.TreeNodeResp;
import com.codereview.entity.Project;
import com.codereview.git.CommitDetail;
import com.codereview.git.CommitInfo;
import com.codereview.git.CommitPage;
import com.codereview.service.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public Result<Project> create(@RequestBody ProjectCreateReq req) {
        return Result.ok(projectService.create(req));
    }

    @GetMapping
    public Result<Page<Project>> list(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(projectService.list(pageNum, pageSize));
    }

    @GetMapping("/{id}/tree")
    public Result<List<TreeNodeResp>> tree(@PathVariable Long id, @RequestParam String branch) {
        return Result.ok(projectService.tree(id, branch));
    }

    @GetMapping("/{id}/branches")
    public Result<List<String>> branches(@PathVariable Long id) {
        return Result.ok(projectService.branches(id));
    }

    @GetMapping("/{id}/commits")
    public Result<List<CommitInfo>> commits(@PathVariable Long id, @RequestParam String branch) {
        return Result.ok(projectService.commits(id, branch));
    }

    @GetMapping("/{id}/changed-files")
    public Result<List<String>> changedFiles(@PathVariable Long id,
                                             @RequestParam String base,
                                             @RequestParam String head) {
        return Result.ok(projectService.changedFiles(id, base, head));
    }

    /** 提交列表（分页）：返回 {commits, hasMore}，供前端滚动加载。 */
    @GetMapping("/{id}/commits/page")
    public Result<CommitPage> commitsPage(@PathVariable Long id,
                                          @RequestParam String branch,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int pageSize) {
        return Result.ok(projectService.commitPage(id, branch, page, pageSize));
    }

    /**
     * 单提交详情（列表视图，不含 patch）。
     * sha 用正则约束为 7–40 位十六进制，避免与 {@code /commits/page} 的路径匹配产生歧义。
     */
    @GetMapping("/{id}/commits/{sha:[0-9a-fA-F]{7,40}}")
    public Result<CommitDetail> commitDetail(@PathVariable Long id, @PathVariable String sha) {
        return Result.ok(projectService.commitDetail(id, sha));
    }

    /** 单个变更文件在该提交中的 patch（按需获取，命中后端缓存）。无 patch 时 data 省略。 */
    @GetMapping("/{id}/commits/{sha:[0-9a-fA-F]{7,40}}/patch")
    public Result<String> filePatch(@PathVariable Long id,
                                    @PathVariable String sha,
                                    @RequestParam String path) {
        return Result.ok(projectService.filePatch(id, sha, path));
    }
}
