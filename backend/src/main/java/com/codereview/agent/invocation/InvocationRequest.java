package com.codereview.agent.invocation;

import com.codereview.agent.prompt.AssembledPrompt;

/**
 * 一次公共模型调用的输入：模型快照、已组装请求与归属。
 *
 * <p>{@code reportId} 只在报告生成调用时填写，便于按报告读取实际请求、返回与错误；
 * 专项调用仍以任务、目标和单元归属。{@code outputReserve} 是调用方对本用途输出预算的
 * 显式要求，仍会受模型声明的最大输出限制；为空时使用公共默认值。
 * {@code turnNo} 只用于 Agent 的轮次追踪，其他用途保持为空。
 */
public record InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope,
                                boolean sendWholeSpecialist, String reportId, Integer outputReserve,
                                Integer turnNo, Integer timeoutSeconds, InvocationPolicy policy) {
    public InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope,
                             boolean sendWholeSpecialist, String reportId, Integer outputReserve,
                             Integer turnNo, Integer timeoutSeconds) {
        this(model, prompt, scope, sendWholeSpecialist, reportId, outputReserve, turnNo, timeoutSeconds, null);
    }

    public InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope) {
        this(model, prompt, scope, false, null, null, null, null);
    }

    public InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope,
                             boolean sendWholeSpecialist) {
        this(model, prompt, scope, sendWholeSpecialist, null, null, null, null);
    }

    public InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope,
                             boolean sendWholeSpecialist, String reportId) {
        this(model, prompt, scope, sendWholeSpecialist, reportId, null, null, null);
    }

    public InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope,
                             boolean sendWholeSpecialist, String reportId, Integer outputReserve) {
        this(model, prompt, scope, sendWholeSpecialist, reportId, outputReserve, null, null);
    }

    public InvocationRequest {
        if (model == null) {
            throw new IllegalArgumentException("缺少模型配置");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("缺少已组装请求");
        }
        if (sendWholeSpecialist && !prompt.purpose().isSpecialist()) {
            throw new IllegalArgumentException("只有专项整文件调用允许将估算作为诊断");
        }
        if (outputReserve != null && outputReserve <= 0) {
            throw new IllegalArgumentException("输出预算必须为正数");
        }
        if (turnNo != null && turnNo < 1) {
            throw new IllegalArgumentException("轮次必须从 1 开始");
        }
        if (timeoutSeconds != null && timeoutSeconds < 1) {
            throw new IllegalArgumentException("单次超时必须为正数秒");
        }
        if (scope == null) {
            scope = InvocationScope.standalone(null);
        }
    }
}
