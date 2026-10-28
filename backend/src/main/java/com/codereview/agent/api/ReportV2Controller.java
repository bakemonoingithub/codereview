package com.codereview.agent.api;

import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.report.ReportGenerationService;
import com.codereview.agent.service.ReportInputService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 报告 v2 入口。
 *
 * <p>候选与冻结是报告生成的前置步骤：候选只返回完整结果，冻结创建一条
 * {@code INPUT_FROZEN} 报告并保存来源快照；正文生成由独立入口选择模型后异步执行。
 * 下载只返回已成功保存的正文，失败报告没有可下载的成功内容。
 */
@RestController
@RequestMapping("/api/v2/reports")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReportV2Controller {

    private final ReportInputService inputService;
    private final ReportGenerationService generationService;

    public ReportV2Controller(ReportInputService inputService,
                              ReportGenerationService generationService) {
        this.inputService = inputService;
        this.generationService = generationService;
    }

    @GetMapping("/candidates")
    public PageResponse<ReportDtos.Candidate> candidates(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return inputService.candidates(projectId, taskId, kind, cursor, limit);
    }

    @PostMapping("/inputs")
    public ResponseEntity<ReportDtos.FrozenInput> freeze(
            @Valid @RequestBody ReportDtos.FreezeRequest request) {
        ReportDtos.FrozenInput frozen = inputService.freeze(request);
        return ResponseEntity.created(URI.create("/api/v2/reports/" + frozen.reportId() + "/inputs"))
                .body(frozen);
    }

    @GetMapping("/{id}/inputs")
    public ReportDtos.FrozenInput inputs(@PathVariable String id) {
        return inputService.frozenInput(id);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam int expectedRevision) {
        generationService.delete(id, expectedRevision);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public PageResponse<ReportDtos.ReportSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return generationService.list(q, state, cursor, limit, pageNum, pageSize);
    }

    /** 受理后返回 202；HTTP 成功只表示进入生成队列，正文状态须继续读取报告。 */
    @PostMapping("/{id}/generate")
    public ResponseEntity<ReportDtos.ReportDetail> generate(
            @PathVariable String id, @Valid @RequestBody ReportDtos.GenerateRequest request) {
        ReportDtos.ReportDetail detail = generationService.start(id, request);
        return ResponseEntity.accepted().body(detail);
    }

    @GetMapping("/{id}")
    public ReportDtos.ReportDetail detail(@PathVariable String id) {
        return generationService.detail(id);
    }

    @GetMapping("/{id}/sources/{ordinal}")
    public ReportDtos.FrozenSource source(@PathVariable String id, @PathVariable int ordinal) {
        return generationService.source(id, ordinal);
    }

    @GetMapping("/{id}/calls")
    public List<ReportDtos.CallSummary> calls(@PathVariable String id) {
        return generationService.calls(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        ReportGenerationService.Download download = generationService.download(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(download.content());
    }
}
