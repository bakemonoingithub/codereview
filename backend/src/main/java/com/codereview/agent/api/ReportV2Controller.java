package com.codereview.agent.api;

import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.service.ReportInputService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 报告输入 v2 入口。
 *
 * <p>候选与冻结是报告生成的前置步骤：候选只返回完整结果，冻结创建一条
 * {@code INPUT_FROZEN} 报告记录并保存来源快照。正文生成在后续任务接入。
 */
@RestController
@RequestMapping("/api/v2/reports")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReportV2Controller {

    private final ReportInputService service;

    public ReportV2Controller(ReportInputService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public PageResponse<ReportDtos.Candidate> candidates(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.candidates(projectId, taskId, kind, cursor, limit);
    }

    @PostMapping("/inputs")
    public ResponseEntity<ReportDtos.FrozenInput> freeze(
            @Valid @RequestBody ReportDtos.FreezeRequest request) {
        ReportDtos.FrozenInput frozen = service.freeze(request);
        return ResponseEntity.created(URI.create("/api/v2/reports/" + frozen.reportId() + "/inputs"))
                .body(frozen);
    }

    @GetMapping("/{id}/inputs")
    public ReportDtos.FrozenInput inputs(@PathVariable String id) {
        return service.frozenInput(id);
    }
}
