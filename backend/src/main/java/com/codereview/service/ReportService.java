package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.ReportGenerateReq;
import com.codereview.entity.Report;
import com.codereview.entity.ReportRecord;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import org.springframework.stereotype.Service;

/**
 * 综合报告（M4）：勾选多条已完成审查记录 → 聚合 → LLM → Markdown。
 */
@Service
public class ReportService {

    private final ReportMapper reportMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final ModelConfigService modelConfigService;
    private final ReportExecutor reportExecutor;

    public ReportService(ReportMapper reportMapper, ReportRecordMapper reportRecordMapper,
                         ModelConfigService modelConfigService, ReportExecutor reportExecutor) {
        this.reportMapper = reportMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.modelConfigService = modelConfigService;
        this.reportExecutor = reportExecutor;
    }

    public Report generate(Long projectId, ReportGenerateReq req) {
        if (req.recordIds() == null || req.recordIds().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择审查记录");
        }
        if (req.modelConfigId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择模型");
        }
        modelConfigService.getOrThrow(req.modelConfigId());
        Report r = new Report();
        r.setProjectId(projectId);
        r.setName(req.name() == null || req.name().isBlank() ? "综合报告" : req.name());
        r.setStatus(0);
        r.setProgress(0);
        reportMapper.insert(r);
        for (Long recordId : req.recordIds()) {
            ReportRecord rr = new ReportRecord();
            rr.setReportId(r.getId());
            rr.setRecordId(recordId);
            reportRecordMapper.insert(rr);
        }
        reportExecutor.execute(r.getId(), req.modelConfigId(), req.promptId());
        return r;
    }

    public Page<Report> list(Long projectId, long pageNum, long pageSize) {
        return reportMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Report>().eq(Report::getProjectId, projectId)
                        .orderByDesc(Report::getCreatedAt));
    }

    public Report detail(Long reportId) {
        Report r = reportMapper.selectById(reportId);
        if (r == null) {
            throw new BusinessException(ResultCode.REPORT_NOT_FOUND);
        }
        return r;
    }
}
