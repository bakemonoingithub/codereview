package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.ReviewRecordResp;
import com.codereview.dto.ReviewTriggerReq;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReviewService {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final ReviewExecutor reviewExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                         ReviewExecutor reviewExecutor) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.reviewExecutor = reviewExecutor;
    }

    public ReviewRecord trigger(Long projectId, ReviewTriggerReq req) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        ReviewRecord record = new ReviewRecord();
        record.setProjectId(projectId);
        record.setBranch(req.branch());
        record.setStatus(0); // 排队
        record.setProgress(0);
        try {
            record.setScopeJson(objectMapper.writeValueAsString(req.scope() == null ? List.of() : req.scope()));
        } catch (Exception e) {
            throw new IllegalStateException("序列化 scope 失败", e);
        }
        reviewRecordMapper.insert(record);
        // 异步执行
        reviewExecutor.execute(record.getId());
        return record;
    }

    public ReviewRecordResp detail(Long reviewId) {
        ReviewRecord r = reviewRecordMapper.selectById(reviewId);
        if (r == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }
        return new ReviewRecordResp(r.getId(), r.getProjectId(), r.getBranch(), r.getCommitSha(),
                r.getStatus(), r.getProgress(), r.getResultJson(), r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt());
    }

    public Page<ReviewRecord> list(Long projectId, long pageNum, long pageSize) {
        return reviewRecordMapper.selectPage(new Page<>(pageNum, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewRecord>()
                        .eq(ReviewRecord::getProjectId, projectId)
                        .orderByDesc(ReviewRecord::getCreatedAt));
    }
}
