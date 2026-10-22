package com.codereview.agent.api;

import com.codereview.agent.dto.PromptDtos;
import com.codereview.agent.service.AgentPromptService;
import com.codereview.common.TextDiff;
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
import java.util.List;

/** 提示词 v2：正文只经版本写入，历史版本不可改，版本对比限制在同一提示词内。 */
@RestController
@RequestMapping("/api/v2/prompts")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class PromptV2Controller {

    private final AgentPromptService service;

    public PromptV2Controller(AgentPromptService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PromptDtos.Summary> list(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) Integer pageNum,
                                               @RequestParam(required = false) Integer pageSize) {
        return service.list(q, cursor, limit, pageNum, pageSize);
    }

    @GetMapping("/{id}")
    public PromptDtos.View detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<PromptDtos.View> create(@Valid @RequestBody PromptDtos.Create req) {
        PromptDtos.View created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v2/prompts/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public PromptDtos.View update(@PathVariable String id, @Valid @RequestBody PromptDtos.Update req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/versions")
    public PromptDtos.View addVersion(@PathVariable String id,
                                      @Valid @RequestBody PromptDtos.VersionCreate req) {
        return service.addVersion(id, req);
    }

    @GetMapping("/{id}/versions")
    public List<PromptDtos.VersionView> versions(@PathVariable String id) {
        return service.versions(id);
    }

    @GetMapping("/{id}/versions/diff")
    public List<TextDiff.DiffLine> diff(@PathVariable String id,
                                        @RequestParam String fromVersionId,
                                        @RequestParam String toVersionId) {
        return service.diff(id, fromVersionId, toVersionId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
