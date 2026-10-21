package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.FeedbackDtos;
import com.codereview.agent.entity.AgentFeedback;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.mapper.AgentFeedbackMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 稳定问题或完整专项结果上的人工反馈。 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class FeedbackService {
    private final AgentDatabase db;

    public FeedbackService(AgentDatabase db) {
        this.db = db;
    }

    public FeedbackDtos.View get(String subjectKind, String subjectId) {
        Subject subject = requireSubject(subjectKind, subjectId);
        AgentFeedback row = find(subject.kind(), subject.id());
        return view(subject, row);
    }

    public FeedbackDtos.View save(String subjectKind, String subjectId, FeedbackDtos.Update request) {
        Subject subject = requireSubject(subjectKind, subjectId);
        if (request.expectedRevision() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "expectedRevision 为必填项");
        }
        AgentFeedbackMapper mapper = db.mapper(AgentFeedbackMapper.class);
        AgentFeedback current = find(subject.kind(), subject.id());
        int expected = request.expectedRevision();
        if (current == null) {
            if (expected != 0) throw conflict(subject, null);
            AgentFeedback row = new AgentFeedback();
            row.setId(AgentIds.next());
            row.setSubjectKind(subject.kind());
            row.setSubjectId(subject.id());
            row.setAssessment(blankToNull(request.assessment()));
            row.setComment(request.comment());
            row.setRevision(1);
            row.setCreatedAt(AgentTime.now());
            row.setUpdatedAt(AgentTime.now());
            try {
                mapper.insert(row);
                return view(subject, row);
            } catch (DuplicateKeyException race) {
                throw conflict(subject, find(subject.kind(), subject.id()));
            }
        }
        if (current.getRevision() != expected) throw conflict(subject, current);
        int changed = mapper.update(null, new LambdaUpdateWrapper<AgentFeedback>()
                .eq(AgentFeedback::getSubjectKind, subject.kind())
                .eq(AgentFeedback::getSubjectId, subject.id())
                .eq(AgentFeedback::getRevision, expected)
                .set(AgentFeedback::getAssessment, blankToNull(request.assessment()))
                .set(AgentFeedback::getComment, request.comment())
                .set(AgentFeedback::getRevision, expected + 1)
                .set(AgentFeedback::getUpdatedAt, AgentTime.now()));
        if (changed == 0) throw conflict(subject, find(subject.kind(), subject.id()));
        return view(subject, find(subject.kind(), subject.id()));
    }

    private Subject requireSubject(String kind, String id) {
        String validId;
        try {
            validId = AgentIds.requireValid(id, "反馈主体 ID");
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "反馈主体 ID 不合法");
        }
        if ("ISSUE".equals(kind)) {
            AgentIssue issue = db.mapper(AgentIssueMapper.class).selectById(validId);
            if (issue == null) throw new ApiException(ErrorCode.NOT_FOUND, "问题不存在");
            AgentReviewResult result = db.mapper(AgentReviewResultMapper.class).selectById(issue.getResultId());
            if (result == null || !"ISSUE".equals(result.getKind()) || !"COMPLETE".equals(result.getCompleteness())) {
                throw new ApiException(ErrorCode.CONFLICT, "仅完整的问题审查结果允许反馈");
            }
            return new Subject("ISSUE", validId);
        }
        if ("RESULT".equals(kind)) {
            AgentReviewResult result = db.mapper(AgentReviewResultMapper.class).selectById(validId);
            if (result == null) throw new ApiException(ErrorCode.NOT_FOUND, "审查结果不存在");
            if (!"COMPLETE".equals(result.getCompleteness())
                    || !("PATTERN".equals(result.getKind()) || "COUPLING".equals(result.getKind()))) {
                throw new ApiException(ErrorCode.CONFLICT, "仅完整的模式或耦合结果允许结果级反馈");
            }
            return new Subject("RESULT", validId);
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "主体类型只能是 ISSUE 或 RESULT");
    }

    private AgentFeedback find(String kind, String id) {
        return db.mapper(AgentFeedbackMapper.class).selectOne(new LambdaQueryWrapper<AgentFeedback>()
                .eq(AgentFeedback::getSubjectKind, kind).eq(AgentFeedback::getSubjectId, id));
    }

    private FeedbackDtos.View view(Subject subject, AgentFeedback row) {
        return row == null
                ? new FeedbackDtos.View(subject.kind(), subject.id(), null, null, 0, null)
                : new FeedbackDtos.View(subject.kind(), subject.id(), row.getAssessment(), row.getComment(),
                        row.getRevision(), row.getUpdatedAt());
    }

    private ApiException conflict(Subject subject, AgentFeedback current) {
        return new ApiException(ErrorCode.REVISION_CONFLICT, "反馈已被其他页面修改；读取最新内容并手动合并后再保存",
                Map.of("current", view(subject, current)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Subject(String kind, String id) { }
}
