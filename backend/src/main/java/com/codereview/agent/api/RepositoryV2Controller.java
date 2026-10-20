package com.codereview.agent.api;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固定版本读取的 v2 入口，供代码浏览与后续材料阅读使用。
 *
 * <p>全部接口要求完整 40 位 SHA；不提供"按分支读取"的入口，避免把可移动引用当作固定版本。
 */
@RestController
@RequestMapping("/api/v2/projects/{projectId}")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class RepositoryV2Controller {

    private final RepositoryReadService service;

    public RepositoryV2Controller(RepositoryReadService service) {
        this.service = service;
    }

    @GetMapping("/paths")
    public RepositorySnapshots.PathList paths(@PathVariable String projectId, @RequestParam String sha) {
        return service.listPaths(projectId, sha);
    }

    @GetMapping("/files")
    public RepositorySnapshots.FileSnapshot file(@PathVariable String projectId,
                                                 @RequestParam String sha,
                                                 @RequestParam String path) {
        return service.readFile(projectId, sha, path);
    }

    @GetMapping("/files/range")
    public RepositorySnapshots.RangeSnapshot range(@PathVariable String projectId,
                                                   @RequestParam String sha,
                                                   @RequestParam String path,
                                                   @RequestParam int startLine,
                                                   @RequestParam int endLine) {
        return service.readRange(projectId, sha, path, startLine, endLine);
    }

    @GetMapping("/diff")
    public RepositorySnapshots.DiffSides diff(@PathVariable String projectId,
                                              @RequestParam String sha,
                                              @RequestParam String path) {
        return service.diff(projectId, sha, path);
    }
}
