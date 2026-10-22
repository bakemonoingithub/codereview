package com.codereview.agent.invocation;

import java.time.LocalDateTime;

/**
 * 调用归属与期限。
 *
 * <p>任务、目标和单元都可以为空：验证调用没有任务，报告调用可能只有任务级归属。
 * 期限为空表示不做任务级期限检查，但仍受单次读超时限制。
 */
public record InvocationScope(String taskId, String targetId, String unitId, LocalDateTime deadlineAt) {

    public static InvocationScope standalone(LocalDateTime deadlineAt) {
        return new InvocationScope(null, null, null, deadlineAt);
    }
}
