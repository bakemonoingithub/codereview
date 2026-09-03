package com.codereview.probe;

import com.codereview.gateway.AiGatewayProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 组合实验断言：JavaParser 结构摘要 → DS 架构解读。
 * 需 {@code -Dprobe.root=} 指向真实 Java 代码目录 + 环境变量 DEEPSEEK_API_KEY。
 */
class StructureLlmProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void analyzeAniSonManageStructure() throws Exception {
        String rootProp = System.getProperty("probe.root");
        assumeTrue(rootProp != null && !rootProp.isBlank(), "跳过：未提供 -Dprobe.root");
        AiGatewayProbe.Config cfg = AiGatewayProbe.defaultConfig();
        assumeTrue(cfg.apiKey() != null && !cfg.apiKey().isBlank(), "跳过：未配置 DEEPSEEK_API_KEY");

        Path root = Path.of(rootProp);
        JavaParserProbe.ProbeResult result = new JavaParserProbe().probe(root);

        // 1. 结构摘要
        String summary = StructureLlmProbe.buildStructureSummary(result);
        System.out.println("=== 结构摘要长度: " + summary.length() + " 字符 ===");
        System.out.println(summary);
        assertFalse(summary.isBlank(), "结构摘要不应为空");

        // 2. DS 分析
        String json = StructureLlmProbe.analyzeStructure(result, cfg);
        System.out.println("=== DS 分析结果 ===");
        System.out.println(json);

        JsonNode node = MAPPER.readTree(json); // 必须合法 JSON
        assertTrue(node.has("architecture_overview"), "应有 architecture_overview");
        assertTrue(node.has("design_patterns") && node.get("design_patterns").isArray(),
                "应有 design_patterns 数组");
        assertTrue(node.has("coupling") && node.get("coupling").isArray(), "应有 coupling 数组");
        assertTrue(node.has("suggestions") && node.get("suggestions").isArray(), "应有 suggestions 数组");
        assertFalse(node.get("architecture_overview").asText().isBlank(), "architecture_overview 不应为空");

        // 3. 写工件（供实验报告引用）
        Path outDir = Path.of("target");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("structure-summary.txt"), summary);
        Files.writeString(outDir.resolve("structure-llm-analysis.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        System.out.println("工件已写出: target/structure-summary.txt, target/structure-llm-analysis.json");
    }
}
