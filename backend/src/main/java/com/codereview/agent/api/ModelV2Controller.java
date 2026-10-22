package com.codereview.agent.api;

import com.codereview.agent.dto.ModelDtos;
import com.codereview.agent.service.AgentModelService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 模型 v2：删除被引用返回 409；验证只证明连通，不证明完整窗口或工具能力。 */
@RestController
@RequestMapping("/api/v2/models")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ModelV2Controller {

    private final AgentModelService service;

    public ModelV2Controller(AgentModelService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ModelDtos.View> list(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) Integer pageNum,
                                               @RequestParam(required = false) Integer pageSize) {
        return service.list(q, cursor, limit, pageNum, pageSize);
    }

    @GetMapping("/{id}")
    public ModelDtos.View detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<ModelDtos.View> create(@Valid @RequestBody ModelDtos.Create req) {
        ModelDtos.View created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v2/models/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ModelDtos.View update(@PathVariable String id, @Valid @RequestBody ModelDtos.Update req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/validate")
    public ModelDtos.ValidationResult validate(@PathVariable String id) {
        return service.validate(id);
    }
}
