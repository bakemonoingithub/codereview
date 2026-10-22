package com.codereview.agent.api;

import com.codereview.agent.dto.AgentTaskDtos;
import com.codereview.agent.service.AgentTaskService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Agent 任务 v2：异步创建返回 202 与公共任务位置；复制参数重新解析版本。
 *
 * <p>执行进度、工具调用与结果读取沿用公共任务端点，由 P33.4/P33.5 接入；
 * 这里不暴露可任意执行工具的入口。
 */
@RestController
@RequestMapping("/api/v2/agent-tasks")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentTaskV2Controller {

    private final AgentTaskService service;

    public AgentTaskV2Controller(AgentTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AgentTaskDtos.Created> create(
            @Valid @RequestBody AgentTaskDtos.Create req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        AgentTaskDtos.Created created = service.create(req, idempotencyKey);
        return ResponseEntity.accepted().location(URI.create(created.detailUrl())).body(created);
    }

    @GetMapping("/{id}/copy-input")
    public AgentTaskDtos.CopyInput copyInput(@PathVariable String id) {
        return service.copyInput(id);
    }
}
