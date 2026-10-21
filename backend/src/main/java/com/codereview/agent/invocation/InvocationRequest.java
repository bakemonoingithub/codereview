package com.codereview.agent.invocation;

import com.codereview.agent.prompt.AssembledPrompt;

/** 一次公共模型调用的输入：模型快照、已组装请求与归属。 */
public record InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope,
                                boolean sendWholeSpecialist) {

    public InvocationRequest(ModelSnapshot model, AssembledPrompt prompt, InvocationScope scope) {
        this(model, prompt, scope, false);
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
        if (scope == null) {
            scope = InvocationScope.standalone(null);
        }
    }
}
