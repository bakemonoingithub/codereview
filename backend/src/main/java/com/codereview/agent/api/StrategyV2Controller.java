package com.codereview.agent.api;

import com.codereview.agent.dto.StrategyDtos;
import com.codereview.agent.service.AgentStrategyService;
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

/** 策略 v2：专项类型 + 模型 + 可选通用规则提示词。 */
@RestController
@RequestMapping("/api/v2/strategies")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class StrategyV2Controller {

    private final AgentStrategyService service;

    public StrategyV2Controller(AgentStrategyService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<StrategyDtos.View> list(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) Integer limit) {
        return service.list(q, type, cursor, limit);
    }

    @GetMapping("/{id}")
    public StrategyDtos.View detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<StrategyDtos.View> create(@Valid @RequestBody StrategyDtos.Create req) {
        StrategyDtos.View created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v2/strategies/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public StrategyDtos.View update(@PathVariable String id, @Valid @RequestBody StrategyDtos.Update req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
