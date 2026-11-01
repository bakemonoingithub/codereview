package com.codereview.agent.prompt;

/**
 * Agent 执行中的纠正事件。
 *
 * <p>执行器只报告"发生了什么"，不拼模型指令；正文由组装层按模板选择。触发条件、
 * 计数、插入位置与消息角色仍留在执行器，因此事件类型变化不会改动循环控制。
 *
 * <p>非法批次事件还会被逐工具返回的诊断复用，纠正正文与 envelope 内的诊断因此只有一份文字。
 */
public sealed interface AgentCorrection {

    /** 模型输出不可用（无法解析为工具调用或提交内容）。 */
    record ModelOutputInvalid(String diagnostic) implements AgentCorrection {
    }

    /** 上一轮只有正文、没有任何工具调用。 */
    record MissingToolCall() implements AgentCorrection {
    }

    /** 单轮工具数量超限，或 submit_result 与其他工具同批。 */
    record InvalidToolBatch(int maxToolsPerTurn) implements AgentCorrection {
    }

    /** 工具调用与返回完全相同，且没有新增材料。 */
    record RepeatedToolCalls() implements AgentCorrection {
    }
}
