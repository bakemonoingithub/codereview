package com.codereview.agent.prompt.template;

import com.codereview.agent.prompt.PromptPurpose;
import com.codereview.agent.specialist.protocol.SpecialistProtocol;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板清单、变量替换与失败边界。
 *
 * <p>这些检查保护抽取本身：清单和资源缺失必须失败，变量必须与声明完全一致，
 * 变量值不能被再次渲染；同时确认资源确实来自构建产物。
 */
class PromptTemplateRegistryTest {

    private final PromptTemplateRegistry registry = PromptTemplateRegistry.loadDefault();

    @Test
    void loadsEveryPurposeInstructionAndSchemaFromClasspath() {
        for (PromptPurpose purpose : PromptPurpose.values()) {
            PromptTemplate instruction = registry.require("instruction." + purpose.wireName());
            PromptTemplate schema = registry.require("protocol." + purpose.wireName());
            assertEquals(purpose.wireName(), instruction.purpose());
            assertEquals("INSTRUCTION", instruction.kind());
            assertEquals("SCHEMA", schema.kind());
            assertTrue(instruction.body().length() > 20, "内置指令正文不应为空");
            assertTrue(schema.body().length() > 20, "协议正文不应为空");
            assertEquals(instruction.body(), registry.require("instruction." + purpose.wireName()).body());
        }
        assertEquals(8, PromptPurpose.values().length);
        assertTrue(registry.ids().contains("protocol.suffix"));
        assertTrue(registry.ids().contains("system.SPECIALIST"));
        assertTrue(registry.ids().contains("agent.TOOL_USAGE"));
    }

    @Test
    void protocolSuffixKeepsVersionAndPurposeInBody() {
        for (PromptPurpose purpose : PromptPurpose.values()) {
            String expected = "\n\n输出协议（protocolVersion=" + SpecialistProtocol.VERSION
                    + "，purpose=" + purpose.wireName() + "）：\n"
                    + registry.require("protocol." + purpose.wireName()).body();
            assertEquals(expected, renderSuffix(purpose));
        }
    }

    @Test
    void everyTemplateResourceComesFromBuildOutput() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL manifest = loader.getResource(PromptTemplateRegistry.MANIFEST_PATH);
        assertNotNull(manifest, "模板清单必须进入构建产物");
        String manifestText = manifest.toString();
        assertTrue(manifestText.contains("/classes/") || manifestText.startsWith("jar:"),
                "测试必须读取构建产物中的清单，而不是源目录：" + manifestText);
        for (String id : registry.ids()) {
            PromptTemplate template = registry.require(id);
            assertNotNull(loader.getResource(template.path()), "模板资源缺失：" + template.path());
            assertFalse(template.body().isBlank(), "模板正文为空：" + id);
        }
    }

    @Test
    void templatesUseLfLineEndings() {
        for (String id : registry.ids()) {
            assertFalse(registry.require(id).body().contains("\r"),
                    "模板必须保持 LF：CRLF 会改变模型正文与容量估算，见 .gitattributes：" + id);
        }
    }

    @Test
    void unknownTemplateFailsExplicitly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.require("instruction.NOT_A_PURPOSE"));
        assertTrue(error.getMessage().contains("未知提示词模板"));
    }

    @Test
    void missingVariableFails() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("protocolVersion", "1");
        values.put("purpose", "ISSUE_FILE");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.render("protocol.suffix", values));
        assertTrue(error.getMessage().contains("缺少变量"));
        assertTrue(error.getMessage().contains("schema"));
    }

    @Test
    void unknownVariableFails() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("protocolVersion", "1");
        values.put("purpose", "ISSUE_FILE");
        values.put("schema", "S");
        values.put("extra", "X");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.render("protocol.suffix", values));
        assertTrue(error.getMessage().contains("未知变量"));
    }

    @Test
    void variableValuesAreNotRenderedRecursively() {
        String rendered = registry.render("protocol.suffix", Map.of(
                "protocolVersion", "1",
                "purpose", "ISSUE_FILE",
                "schema", "{{purpose}} 与 $1 原样保留"));
        assertTrue(rendered.endsWith("{{purpose}} 与 $1 原样保留"));
        assertFalse(rendered.contains("purpose=ISSUE_FILE 与"), "变量值不能被再次渲染");
    }

    @Test
    void missingResourceFailsAtLoad() {
        String manifest = manifest("""
                {"id":"system.BROKEN","kind":"SYSTEM","version":"1",
                 "path":"prompts/system/missing.txt","variables":[]}""");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptTemplateRegistry.load(new StringReader(manifest), path -> null));
        assertTrue(error.getMessage().contains("缺少提示词模板资源"));
        assertTrue(error.getMessage().contains("prompts/system/missing.txt"));
    }

    @Test
    void undeclaredPlaceholderFailsAtLoad() {
        String manifest = manifest("""
                {"id":"system.BROKEN","kind":"SYSTEM","version":"1",
                 "path":"prompts/system/broken.txt","variables":[]}""");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptTemplateRegistry.load(new StringReader(manifest), path -> "正文 {{unknown}}"));
        assertTrue(error.getMessage().contains("未声明变量"));
    }

    @Test
    void unusedDeclaredVariableFailsAtLoad() {
        String manifest = manifest("""
                {"id":"system.BROKEN","kind":"SYSTEM","version":"1",
                 "path":"prompts/system/broken.txt","variables":["unused"]}""");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptTemplateRegistry.load(new StringReader(manifest), path -> "正文"));
        assertTrue(error.getMessage().contains("未使用的变量"));
    }

    @Test
    void unreadablePlaceholderFailsAtLoad() {
        String manifest = manifest("""
                {"id":"system.BROKEN","kind":"SYSTEM","version":"1",
                 "path":"prompts/system/broken.txt","variables":[]}""");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptTemplateRegistry.load(new StringReader(manifest), path -> "正文 {{ 错误 }}"));
        assertTrue(error.getMessage().contains("无法识别的占位符"));
    }

    @Test
    void duplicateIdentifierFailsAtLoad() {
        String entry = """
                {"id":"system.DUP","kind":"SYSTEM","version":"1",
                 "path":"prompts/system/dup.txt","variables":[]}""";
        String manifest = "{\"schemaVersion\":1,\"templates\":[" + entry + "," + entry + "]}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptTemplateRegistry.load(new StringReader(manifest), path -> "正文"));
        assertTrue(error.getMessage().contains("标识重复"));
    }

    @Test
    void unsupportedManifestVersionFailsAtLoad() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptTemplateRegistry.load(new StringReader("{\"schemaVersion\":99,\"templates\":[]}"),
                        path -> "正文"));
        assertTrue(error.getMessage().contains("结构版本"));
    }

    @Test
    void manifestBodyAndVariablesArePreservedExactly() {
        String manifest = manifest("""
                {"id":"system.EXACT","kind":"SYSTEM","version":"1",
                 "path":"prompts/system/exact.txt","variables":["name"]}""");
        String body = "  缩进保留\n\n{{name}}\n";
        PromptTemplateRegistry custom = PromptTemplateRegistry.load(new StringReader(manifest), path -> body);
        PromptTemplate template = custom.require("system.EXACT");
        assertEquals(body, template.body());
        assertEquals("1", template.version());
        assertEquals(body.replace("{{name}}", "值"), custom.render("system.EXACT", Map.of("name", "值")));
    }

    private String renderSuffix(PromptPurpose purpose) {
        return registry.render("protocol.suffix", Map.of(
                "protocolVersion", SpecialistProtocol.VERSION,
                "purpose", purpose.wireName(),
                "schema", registry.require("protocol." + purpose.wireName()).body()));
    }

    private static String manifest(String entry) {
        return "{\"schemaVersion\":1,\"templates\":[" + entry + "]}";
    }
}
