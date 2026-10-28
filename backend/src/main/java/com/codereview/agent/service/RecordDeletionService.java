package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.execution.TaskStates;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.report.ReportStates;
import com.codereview.agent.support.AgentIds;

/** 与生成、选材共用根行锁。删除只改变根记录，历史报告快照不变。 */
public final class RecordDeletionService {
    private final AgentDatabase db;

    public RecordDeletionService(AgentDatabase db) { this.db = db; }

    public void task(String id, int expectedRevision) {
        AgentIds.requireValid(id, "审查 ID");
        revision(expectedRevision);
        db.inTransaction(() -> {
            var mapper = db.mapper(AgentReviewTaskMapper.class);
            var task = mapper.selectIncludingDeletedForUpdate(id);
            if (task == null) throw missing();
            if (Integer.valueOf(1).equals(task.getIsDeleted())) return null;
            if (!TaskStates.isTerminal(task.getState())) {
                throw new ApiException(ErrorCode.CONFLICT, "审查尚未结束，请等待结束或取消后再删除");
            }
            if (task.getRevision() != expectedRevision || mapper.markDeleted(id, expectedRevision) != 1) {
                throw conflict();
            }
            return null;
        });
    }

    public void report(String id, int expectedRevision) {
        AgentIds.requireValid(id, "报告 ID");
        revision(expectedRevision);
        db.inTransaction(() -> {
            var mapper = db.mapper(AgentReportMapper.class);
            var report = mapper.selectIncludingDeletedForUpdate(id);
            if (report == null) throw missing();
            if (Integer.valueOf(1).equals(report.getIsDeleted())) return null;
            if (!(ReportStates.isTerminal(report.getState()) || ReportStates.INPUT_FROZEN.equals(report.getState()))) {
                throw new ApiException(ErrorCode.CONFLICT, "报告正在排队或生成，请等待结束后再删除");
            }
            if (report.getRevision() != expectedRevision || mapper.markDeleted(id, expectedRevision) != 1) {
                throw conflict();
            }
            return null;
        });
    }

    private static void revision(int value) {
        if (value < 0) throw new ApiException(ErrorCode.VALIDATION_FAILED, "版本号不能为负数");
    }
    private static ApiException missing() { return new ApiException(ErrorCode.NOT_FOUND, "记录不存在"); }
    private static ApiException conflict() {
        return new ApiException(ErrorCode.REVISION_CONFLICT, "记录已被其他操作修改，请刷新后重试");
    }
}
