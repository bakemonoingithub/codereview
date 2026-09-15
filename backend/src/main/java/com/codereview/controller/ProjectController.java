package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.dto.DeleteImpactResp;
import com.codereview.dto.FileContentResp;
import com.codereview.dto.ProjectCreateReq;
import com.codereview.dto.TreeNodeResp;
import com.codereview.entity.Project;
import com.codereview.git.CommitDetail;
import com.codereview.git.CommitInfo;
import com.codereview.git.CommitPage;
import com.codereview.service.ProjectService;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /** 项目详情：供详情页显示项目名/仓库地址（凭据字段为 WRITE_ONLY，不会回传）。 */
    @GetMapping("/{id}")
    public Result<Project> detail(@PathVariable Long id) {
        return Result.ok(projectService.detail(id));
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

    /**
     * 文件查看：返回**已截断**的文本与截断元信息。
     *
     * <p>与上面那个 patch 接口的区别是刻意的：patch 接口返回裸字符串（审查结果页在用，不能改形状），
     * 而这里要带 {@code truncated}/{@code totalLines} 让界面说清"已显示前 1000 行，共 N 行"，
     * 所以走 JSON 信封。默认 1000 行，{@code full=true} 放宽到 20000 行且始终受 2MB 字节上限约束。
     *
     * @param ref  分支名或 sha（sha 才能保证"看到的是那个提交的内容"）
     * @param path 仓库内相对路径（服务端会拒绝绝对路径与 {@code ..}，并二次校验是否可查看）
     * @param mode {@code content}=文件原文（默认），{@code diff}=该提交对该文件的差异
     * @param full 是否放宽上限（界面上的「加载全文」）
     */
    @GetMapping("/{id}/file")
    public Result<FileContentResp> file(@PathVariable Long id,
                                        @RequestParam String ref,
                                        @RequestParam String path,
                                        @RequestParam(defaultValue = "content") String mode,
                                        @RequestParam(defaultValue = "false") boolean full) {
        return Result.ok(projectService.fileView(id, ref, path, mode, full));
    }

    /**
     * 删除前的**影响范围预览**：前端拿它弹确认框，让用户知道会一并销毁多少记录与报告。
     * 同时告知"是否因有正在进行的审查而被拒绝"，避免点了删除才知道删不了。
     */
    @GetMapping("/{id}/delete-impact")
    public Result<DeleteImpactResp> deleteImpact(@PathVariable Long id) {
        return Result.ok(projectService.deleteImpact(id));
    }

    /**
     * 删除项目：逻辑删除项目本身，并级联删除它的审查记录、报告与 issue 标记。
     *
     * <p>存在"正在进行的审查"时返回业务错误（1005），由前端展示具体原因。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok();
    }
}
