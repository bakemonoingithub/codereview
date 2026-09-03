package com.codereview.probe;

import com.codereview.gateway.AiGatewayProbe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 组合实验探针：JavaParser 结构摘要 → DeepSeek 架构解读。
 * <p>
 * 验证系统方案设计中的两条关键原则：
 * <ul>
 *   <li>「结构摘要替代原文」——跨文件分析只喂结构摘要，不喂原始代码；</li>
 *   <li>「确定性优先」——结构类结论以 JavaParser 抽取为准，LLM 只做解读。</li>
 * </ul>
 * 流程：JavaParserProbe 抽取 AniSonManage 的结构 → 压缩成紧凑结构摘要 → 发给 DS
 * 做分层/设计模式/耦合分析 → 返回结构化 JSON。
 */
public final class StructureLlmProbe {

    private StructureLlmProbe() {
    }

    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是一名资深 Java 架构与代码审查专家。
            我会给你一份「项目结构摘要」（由 JavaParser 静态抽取的包/类/接口/字段/方法签名/依赖边，不含源码正文）。
            请基于结构摘要做静态架构分析，只输出合法 JSON，不要输出任何其他文字。
            """;

    public static final String DEFAULT_ANALYSIS_PROMPT = """
            请分析下面这份项目结构摘要，输出 JSON，格式固定为：
            {
              "architecture_overview": "一段话：分层架构、各层职责、依赖方向是否合理",
              "layering": [{"layer": "controller|service|repository|entity|dto|vo|exception|其他", "classes": ["类名"], "note": "说明"}],
              "design_patterns": [{"name": "模式名", "participants": ["参与类"], "evidence": "从结构看出的依据", "confidence": "高|中|低"}],
              "coupling": [{"from": "依赖方", "to": "被依赖方", "level": "高|中|低", "reason": "原因"}],
              "suggestions": ["改进建议1", "改进建议2"]
            }
            只输出这一个 JSON 对象，不要输出任何其他文字。
            """;

    /** 把 JavaParser 的抽取结果压缩成紧凑结构摘要（约一个 token 高效的文本）。 */
    public static String buildStructureSummary(JavaParserProbe.ProbeResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目包根: ").append(result.projectRootPackage()).append("\n\n");
        for (JavaParserProbe.FileSummary f : result.files()) {
            if (f.types().isEmpty()) {
                continue;
            }
            List<String> internalDeps = new ArrayList<>();
            for (JavaParserProbe.ImportSummary imp : f.imports()) {
                if ("internal".equals(imp.kind())) {
                    internalDeps.add(shortName(imp.name()));
                }
            }
            for (JavaParserProbe.TypeSummary t : f.types()) {
                sb.append('[').append(t.kind()).append("] ")
                        .append(f.packageName()).append('.').append(t.name()).append('\n');
                if (!t.annotations().isEmpty()) {
                    sb.append("  @").append(String.join(" @", t.annotations())).append('\n');
                }
                if (!t.extendedTypes().isEmpty()) {
                    sb.append("  extends ").append(String.join(", ", t.extendedTypes())).append('\n');
                }
                if (!t.implementedTypes().isEmpty()) {
                    sb.append("  implements ").append(String.join(", ", t.implementedTypes())).append('\n');
                }
                for (JavaParserProbe.FieldSummary field : t.fields()) {
                    sb.append("  field ").append(field.type()).append(' ').append(field.name()).append('\n');
                }
                for (String m : t.methods()) {
                    sb.append("  method ").append(m).append('\n');
                }
                if (!internalDeps.isEmpty()) {
                    sb.append("  internal_deps: ").append(String.join(", ", internalDeps)).append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 结构摘要 + 提示词 → DS → 返回分析 JSON 字符串。 */
    public static String analyzeStructure(JavaParserProbe.ProbeResult result, AiGatewayProbe.Config cfg)
            throws Exception {
        String summary = buildStructureSummary(result);
        AiGatewayProbe.ChatResult r = new AiGatewayProbe().chat(
                cfg, DEFAULT_SYSTEM_PROMPT, summary + "\n\n" + DEFAULT_ANALYSIS_PROMPT);
        return r.content();
    }

    private static String shortName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }

    /** CLI：java -cp ... StructureLlmProbe <rootDir> */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: StructureLlmProbe <rootDir>");
            System.exit(2);
        }
        AiGatewayProbe.Config cfg = AiGatewayProbe.defaultConfig();
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            System.err.println("未配置 DEEPSEEK_API_KEY（环境变量或 -Ddeepseek.api-key）");
            System.exit(2);
        }
        JavaParserProbe.ProbeResult result = new JavaParserProbe().probe(Path.of(args[0]));
        String json = analyzeStructure(result, cfg);
        System.out.println(json);
    }
}
