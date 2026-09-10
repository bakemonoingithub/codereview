package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.dto.ProjectCreateReq;
import com.codereview.dto.TreeNodeResp;
import com.codereview.entity.Project;
import com.codereview.git.CommitInfo;
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
}
