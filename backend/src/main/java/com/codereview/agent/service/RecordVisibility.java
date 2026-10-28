package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;

/** 子记录不得绕过所属根记录的可见性检查。 */
public final class RecordVisibility {
    private RecordVisibility() { }

    public static AgentReviewTask task(AgentDatabase db, String id) {
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class).selectById(id);
        if (task == null || Integer.valueOf(1).equals(task.getIsDeleted())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "审查不存在或已删除");
        }
        return task;
    }

    public static void call(AgentDatabase db, AgentModelCall call) {
        if (call.getTaskId() != null) task(db, call.getTaskId());
        if (call.getReportId() != null) {
            var report = db.mapper(AgentReportMapper.class).selectById(call.getReportId());
            if (report == null || Integer.valueOf(1).equals(report.getIsDeleted())) {
                throw new ApiException(ErrorCode.NOT_FOUND, "报告不存在或已删除");
            }
        }
    }
}
