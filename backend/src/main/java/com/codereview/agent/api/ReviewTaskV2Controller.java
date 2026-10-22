package com.codereview.agent.api;

import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.service.TaskService;
import com.codereview.agent.service.TaskStatisticsService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 任务 v2 入口。
 *
 * <p>异步创建返回 202 和 location；HTTP 成功不代表任务成功，客户端必须继续读取 state。
 * 取消与复制参数都不回传凭据或旧材料正文。
 */
@RestController
@RequestMapping("/api/v2/review-tasks")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReviewTaskV2Controller {

    private final TaskService service;
    private final TaskStatisticsService statisticsService;

    public ReviewTaskV2Controller(TaskService service, TaskStatisticsService statisticsService) {
        this.service = service;
        this.statisticsService = statisticsService;
    }

    @PostMapping
    public ResponseEntity<TaskDtos.View> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TaskDtos.Create req) {
        TaskDtos.View created = service.create(req, idempotencyKey);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v2/review-tasks/" + created.id()))
                .body(created);
    }

    @GetMapping
    public PageResponse<TaskDtos.Summary> list(@RequestParam(required = false) String projectId,
                                               @RequestParam(required = false) String route,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String state,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) Integer pageNum,
                                               @RequestParam(required = false) Integer pageSize) {
        return service.list(projectId, route, type, state, q, cursor, limit, pageNum, pageSize);
    }

    @GetMapping("/{id}")
    public TaskDtos.View detail(@PathVariable String id) {
        return service.detail(id);
    }

    /** 任务级人工判定统计；问题级与结果级分别返回，不由前端把两者混成一个比率。 */
    @GetMapping("/{id}/statistics")
    public TaskDtos.Statistics statistics(@PathVariable String id) {
        return statisticsService.statistics(id);
    }

    @PostMapping("/{id}/cancel")
    public TaskDtos.View cancel(@PathVariable String id, @Valid @RequestBody TaskDtos.Cancel req) {
        return service.cancel(id, req.expectedRevision());
    }

    @GetMapping("/{id}/copy-input")
    public TaskDtos.CopyInput copyInput(@PathVariable String id) {
        return service.copyInput(id);
    }

    @GetMapping("/{id}/calls")
    public PageResponse<TaskDtos.CallSummary> calls(@PathVariable String id,
                                                    @RequestParam(required = false) String targetId,
                                                    @RequestParam(required = false) String unitId,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit) {
        return service.calls(id, targetId, unitId, cursor, limit);
    }

    @GetMapping("/{id}/calls/{callId}")
    public TaskDtos.CallDetailFull call(@PathVariable String id, @PathVariable String callId) {
        return service.callDetailFull(id, callId);
    }

    @GetMapping("/{id}/targets/{targetId}/result")
    public TaskDtos.ResultView result(@PathVariable String id, @PathVariable String targetId) {
        return service.result(id, targetId);
    }

    @GetMapping("/{id}/targets/{targetId}/materials")
    public java.util.List<TaskDtos.MaterialView> materials(@PathVariable String id,
                                                           @PathVariable String targetId) {
        return service.materials(id, targetId);
    }

    @GetMapping("/{id}/materials/{materialId}")
    public TaskDtos.MaterialDetail material(@PathVariable String id,
                                            @PathVariable String materialId) {
        return service.material(id, materialId);
    }
}
