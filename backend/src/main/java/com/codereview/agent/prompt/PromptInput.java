package com.codereview.agent.prompt;

import java.util.List;

/**
 * 一次组装的类型化输入。
 *
 * <p>通用规则、文件说明、材料与缺失信息都由这里进入；调用方不直接拼提示词。
 * {@code formatRetry} 只携带实际错误和有界返回片段，长度由组装服务再次裁剪。
 * 报告用途另带标题与补充要求，避免把报告要求伪装成通用规则或目标材料。
 */
public record PromptInput(PromptPurpose purpose,
                          String protocolVersion,
                          String generalRuleName,
                          String generalRuleContent,
                          String targetNote,
                          String sourceKind,
                          String branch,
                          String headSha,
                          String baseSha,
                          List<PromptMaterial> targets,
                          List<PromptMaterial> auxiliary,
                          List<String> missingNotes,
                          List<PromptFact> facts,
                          FormatRetry formatRetry,
                          String reportTitle,
                          String reportInstruction) {

    /** 既有专项／验证调用保持原构造方式；报告用途再显式传入标题与要求。 */
    public PromptInput(PromptPurpose purpose, String protocolVersion, String generalRuleName,
                       String generalRuleContent, String targetNote, String sourceKind, String branch,
                       String headSha, String baseSha, List<PromptMaterial> targets,
                       List<PromptMaterial> auxiliary, List<String> missingNotes,
                       List<PromptFact> facts, FormatRetry formatRetry) {
        this(purpose, protocolVersion, generalRuleName, generalRuleContent, targetNote, sourceKind, branch,
                headSha, baseSha, targets, auxiliary, missingNotes, facts, formatRetry, null, null);
    }

    public PromptInput {
        if (purpose == null) {
            throw new IllegalArgumentException("缺少提示词用途");
        }
        targets = targets == null ? List.of() : List.copyOf(targets);
        auxiliary = auxiliary == null ? List.of() : List.copyOf(auxiliary);
        missingNotes = missingNotes == null ? List.of() : List.copyOf(missingNotes);
        facts = facts == null ? List.of() : List.copyOf(facts);
        if (protocolVersion == null || protocolVersion.isBlank()) {
            protocolVersion = "1";
        }
    }

    /** 格式重试诊断：错误码与上一次返回内容的有界引用，不作为新指令。 */
    public record FormatRetry(int attempt, String errorCode, String previousResponseExcerpt) {
        public FormatRetry {
            if (attempt < 1) {
                throw new IllegalArgumentException("格式重试次数必须从 1 开始");
            }
        }
    }
}
