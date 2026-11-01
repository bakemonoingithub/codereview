package com.codereview.agent.specialist.protocol;

/**
 * 三类专项的版本化输出协议标识。
 *
 * <p>协议正文随应用发布，位于 classpath 的 {@code prompts/protocol/<用途>.txt}，并由
 * {@code prompts/manifest.json} 关联到用途；本类只保留协议与结果封套版本，供结果保存和
 * 历史阅读使用。{@code PromptAssemblyService} 负责把内置指令与协议放进 system 消息，
 * 调用方不能自行追加另一套 schema。
 *
 * <p>协议文本本身只做结构、类型、必填和位置范围检查；不判断问题真伪、模式是否经典或耦合
 * 解释是否正确，这些留给人工反馈。
 */
public final class SpecialistProtocol {

    /** 协议与结果封套版本；字段不兼容时递增。 */
    public static final String VERSION = "1";
    /** 内置指令版本；与用户策略/文件说明分开，便于历史调用回看。 */
    public static final String INSTRUCTION_VERSION = "1";

    private SpecialistProtocol() {
    }
}
