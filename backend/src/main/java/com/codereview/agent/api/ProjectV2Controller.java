package com.codereview.agent.api;

import com.codereview.agent.dto.ProjectDtos;
import com.codereview.agent.service.AgentProjectService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 项目 v2：新建时可写仓库地址与令牌，编辑只改名称与新令牌。 */
@RestController
@RequestMapping("/api/v2/projects")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ProjectV2Controller {

    private final AgentProjectService service;

    public ProjectV2Controller(AgentProjectService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProjectDtos.View> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return service.list(q, cursor, limit);
    }

    @GetMapping("/{id}")
    public ProjectDtos.View detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<ProjectDtos.View> create(@Valid @RequestBody ProjectDtos.Create req) {
        ProjectDtos.View created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v2/projects/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ProjectDtos.View update(@PathVariable String id, @Valid @RequestBody ProjectDtos.Update req) {
        return service.update(id, req);
    }
}
