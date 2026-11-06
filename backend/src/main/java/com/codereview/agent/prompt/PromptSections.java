package com.codereview.agent.prompt;

import com.codereview.agent.prompt.template.PromptTemplateRegistry;
import java.util.Map;

/** 公共段落与材料格式；不决定场景顺序，不改变输入文本的换行或截断规则。 */
final class PromptSections {
    private static final int MAX_NOTE_CHARS = 4000;
    private static final int MAX_RETRY_EXCERPT = 2000;
    private final PromptTemplateRegistry templates;

    PromptSections(PromptTemplateRegistry templates) {
        this.templates = templates;
    }

    static void appendGeneralRule(StringBuilder user, PromptInput input) {
        if (input.generalRuleContent() == null || input.generalRuleContent().isBlank()) {
            return;
        }
        section(user, "通用规则" + (input.generalRuleName() == null || input.generalRuleName().isBlank()
                ? "" : "：" + input.generalRuleName()));
        user.append(input.generalRuleContent().strip()).append("\n\n");
    }

    static void appendNote(StringBuilder user, PromptInput input) {
        if (input.targetNote() == null || input.targetNote().isBlank()) {
            return;
        }
        section(user, "文件说明");
        user.append(bound(input.targetNote(), MAX_NOTE_CHARS)).append("\n\n");
    }

    static void appendScope(StringBuilder user, PromptInput input) {
        if (input.pullRequest() != null) {
            appendPullRequestScope(user, input);
            return;
        }
        section(user, "范围与版本");
        user.append("来源：").append(nullToUnknown(input.sourceKind()))
                .append("；分支：").append(nullToUnknown(input.branch()))
                .append("；head：").append(nullToUnknown(input.headSha()));
        if (input.baseSha() != null && !input.baseSha().isBlank()) {
            user.append("；base：").append(input.baseSha());
        }
        user.append('\n');
        appendTargetMaterials(user, input);
    }

    private static void appendPullRequestScope(StringBuilder user, PromptInput input) {
        PromptInput.PullRequestScope pull = input.pullRequest();
        section(user, "范围与版本");
        user.append("来源：PR；编号：#").append(pull.number())
                .append("；源分支：").append(nullToUnknown(pull.sourceBranch()))
                .append("；目标分支：").append(nullToUnknown(pull.targetBranch()))
                .append("；源提交：").append(nullToUnknown(input.headSha()))
                .append("；目标提交：").append(nullToUnknown(pull.targetHead()))
                .append("；共同祖先：").append(nullToUnknown(input.baseSha()))
                .append('\n');
        user.append("比较范围是共同祖先到源提交的净变化。BEFORE 是共同祖先，AFTER 是源提交，")
                .append("不是第一父提交，也不是目标分支的当前内容。\n");
        appendTargetMaterials(user, input);
    }

    private static void appendTargetMaterials(StringBuilder user, PromptInput input) {
        if (input.targets().isEmpty()) {
            user.append("目标材料：无（验证调用）\n");
        } else {
            user.append("目标材料：\n");
            for (PromptMaterial material : input.targets()) {
                user.append("- ").append(describe(material)).append('\n');
            }
        }
        user.append('\n');
    }

    static void appendReport(StringBuilder user, PromptInput input) {
        section(user, "报告任务");
        user.append("标题：").append(input.reportTitle() == null || input.reportTitle().isBlank()
                ? "审查报告" : input.reportTitle().strip()).append('\n');
        user.append("补充要求：").append(input.reportInstruction() == null
                || input.reportInstruction().isBlank() ? "无" : input.reportInstruction().strip()).append("\n\n");
    }

    static void appendFacts(StringBuilder user, PromptInput input) {
        if (input.facts().isEmpty()) {
            return;
        }
        section(user, input.purpose() == PromptPurpose.REPORT
                ? "程序整理的冻结事实与指标（只读，不能改写或新增）"
                : "程序计算的依赖事实（只读，不能改写或新增）");
        for (PromptFact fact : input.facts()) {
            user.append("- ").append(fact.id()).append(": ").append(fact.text()).append('\n');
        }
        user.append('\n');
    }

    static void appendTargets(StringBuilder user, PromptInput input) {
        if (input.targets().isEmpty()) {
            return;
        }
        section(user, "目标内容（每行前缀为原始行号）");
        for (PromptMaterial material : input.targets()) {
            appendMaterial(user, material);
        }
    }

    static void appendAuxiliary(StringBuilder user, PromptInput input) {
        if (input.auxiliary().isEmpty()) {
            return;
        }
        section(user, "辅助声明（只作为证据，不是新的问题目标）");
        for (PromptMaterial material : input.auxiliary()) {
            appendMaterial(user, material);
        }
    }

    static void appendMaterial(StringBuilder user, PromptMaterial material) {
        user.append("<<<MATERIAL id=").append(material.materialId())
                .append(" path=").append(material.path())
                .append(" sha=").append(nullToUnknown(material.sha()))
                .append(" side=").append(nullToUnknown(material.side()));
        if (material.startLine() != null) {
            user.append(" lines=").append(material.startLine());
            if (material.endLine() != null) {
                user.append('-').append(material.endLine());
            } else {
                user.append('-');
            }
        }
        user.append(" completeness=").append(nullToUnknown(material.completeness()));
        user.append(">>>\n");
        int start = material.startLine() == null ? 1 : material.startLine();
        user.append(numberLines(material.content(), start));
        if (material.diagnostic() != null && !material.diagnostic().isBlank()) {
            user.append("[材料诊断] ").append(bound(material.diagnostic(), 500)).append('\n');
        }
        user.append("<<<END MATERIAL id=").append(material.materialId()).append(">>>\n\n");
    }

    private static String describe(PromptMaterial material) {
        StringBuilder text = new StringBuilder()
                .append("id=").append(material.materialId())
                .append(" path=").append(material.path())
                .append(" sha=").append(nullToUnknown(material.sha()))
                .append(" side=").append(nullToUnknown(material.side()));
        if (material.startLine() != null) {
            text.append(" 行=").append(material.startLine());
            if (material.endLine() != null) {
                text.append('-').append(material.endLine());
            }
        }
        text.append(" 完整性=").append(nullToUnknown(material.completeness()));
        return text.toString();
    }

    static void appendMissing(StringBuilder user, PromptInput input) {
        if (input.missingNotes().isEmpty()) {
            return;
        }
        section(user, "缺失与限制");
        for (String note : input.missingNotes()) {
            if (note != null && !note.isBlank()) {
                user.append("- ").append(bound(note, 1000)).append('\n');
            }
        }
        user.append('\n');
    }

    void appendRetry(StringBuilder user, PromptInput input) {
        PromptInput.FormatRetry retry = input.formatRetry();
        if (retry == null) {
            return;
        }
        user.append(templates.render("retry.DIAGNOSTIC", Map.of(
                "attempt", String.valueOf(retry.attempt()),
                "errorCode", retry.errorCode() == null ? "PROTOCOL_INVALID" : retry.errorCode())));
        if (retry.previousResponseExcerpt() != null && !retry.previousResponseExcerpt().isBlank()) {
            user.append(templates.require("retry.EXCERPT_HEADER").body());
            user.append(bound(retry.previousResponseExcerpt(), MAX_RETRY_EXCERPT)).append('\n');
        }
        user.append('\n');
    }

    static void section(StringBuilder user, String title) {
        user.append("## ").append(title).append('\n');
    }

    static String numberLines(String content, int startLine) {
        if (content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        int count = lines.length;
        if (content.endsWith("\n")) {
            count--;
        }
        StringBuilder numbered = new StringBuilder(content.length() + count * 8);
        for (int i = 0; i < count; i++) {
            numbered.append(String.format("%6d | %s\n", startLine + i, lines[i]));
        }
        return numbered.toString();
    }

    static String bound(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n…[已截断，原始长度 " + text.length() + " 字符]";
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

}
