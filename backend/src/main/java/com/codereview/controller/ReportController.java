package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.dto.ReportGenerateReq;
import com.codereview.entity.Report;
import com.codereview.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/projects/{projectId}/reports")
    public Result<Report> generate(@PathVariable Long projectId, @RequestBody ReportGenerateReq req) {
        return Result.ok(reportService.generate(projectId, req));
    }

    @GetMapping("/projects/{projectId}/reports")
    public Result<Page<Report>> list(@PathVariable Long projectId,
                                     @RequestParam(defaultValue = "1") long pageNum,
                                     @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(reportService.list(projectId, pageNum, pageSize));
    }

    @GetMapping("/reports/{id}")
    public Result<Report> detail(@PathVariable Long id) {
        return Result.ok(reportService.detail(id));
    }
}
