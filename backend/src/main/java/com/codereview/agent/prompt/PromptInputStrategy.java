package com.codereview.agent.prompt;

/** 普通类型化输入的组装策略；Agent 和工具验证保留各自的类型化入口。 */
interface PromptInputStrategy {
    AssembledPrompt assemble(PromptInput input);
}
