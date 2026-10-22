package com.codereview.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * envelope 的序列化与体积检查。
 *
 * <p>工具返回必须先满足 32 KiB 总上限再交给模型；调用方应在分页阶段就裁剪，
 * 这里只做最后一道断言 —— 超限直接失败，绝不静默截断 JSON 导致模型收到半截结构。
 */
public final class ToolEnvelopes {

    private ToolEnvelopes() {
    }

    public static byte[] write(ObjectMapper mapper, ToolEnvelope envelope) {
        try {
            return mapper.writeValueAsBytes(map(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具返回序列化失败", e);
        }
    }

    public static String writeString(ObjectMapper mapper, ToolEnvelope envelope) {
        return new String(write(mapper, envelope), StandardCharsets.UTF_8);
    }

    public static int size(ObjectMapper mapper, ToolEnvelope envelope) {
        return write(mapper, envelope).length;
    }

    public static void requireWithinLimit(ObjectMapper mapper, ToolEnvelope envelope) {
        int size = size(mapper, envelope);
        if (size > ToolLimits.ENVELOPE_MAX_BYTES) {
            throw new IllegalStateException("工具返回超过 " + ToolLimits.ENVELOPE_MAX_BYTES
                    + " 字节上限：" + size);
        }
    }

    private static Map<String, Object> map(ToolEnvelope envelope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toolCallId", envelope.toolCallId());
        body.put("status", envelope.status());
        if (!envelope.versionRefs().isEmpty()) {
            List<Map<String, Object>> refs = new ArrayList<>();
            for (ToolEnvelope.VersionRef ref : envelope.versionRefs()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("version", ref.version());
                item.put("sha", ref.sha());
                refs.add(item);
            }
            body.put("versionRefs", refs);
        }
        if (envelope.comparisonId() != null) {
            body.put("comparisonId", envelope.comparisonId());
        }
        if (!envelope.materials().isEmpty()) {
            List<Map<String, Object>> materials = new ArrayList<>();
            for (ToolEnvelope.ToolMaterial material : envelope.materials()) {
                materials.add(material(material));
            }
            body.put("materials", materials);
        }
        if (envelope.data() != null) {
            body.put("data", envelope.data());
        }
        body.put("completeness", envelope.completeness());
        if (envelope.nextCursor() != null) {
            body.put("nextCursor", envelope.nextCursor());
        }
        if (envelope.diagnostic() != null) {
            body.put("diagnostic", envelope.diagnostic());
        }
        return body;
    }

    private static Map<String, Object> material(ToolEnvelope.ToolMaterial material) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialId", material.materialId());
        item.put("path", material.path());
        if (material.side() != null) {
            item.put("side", material.side());
        }
        if (material.sha() != null) {
            item.put("sha", material.sha());
        }
        if (material.startLine() != null) {
            item.put("startLine", material.startLine());
        }
        if (material.endLine() != null) {
            item.put("endLine", material.endLine());
        }
        if (material.contentHash() != null) {
            item.put("contentHash", material.contentHash());
        }
        if (material.completeness() != null) {
            item.put("completeness", material.completeness());
        }
        if (material.content() != null) {
            item.put("content", material.content());
        }
        return item;
    }
}
