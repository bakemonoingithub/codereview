package com.codereview.agent.tools.protocol;

import com.codereview.agent.tools.capability.ToolCapability;
import com.codereview.agent.tools.capability.ToolParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型协议适配层：把中立能力定义转成当前使用的 OpenAI Chat {@code tools} JSON。
 *
 * <p>只有本层知道 {@code type=function}、{@code function.parameters} 等请求字段；
 * 能力定义本身与供应商无关。属性顺序沿用改造前的序列化顺序，因为工具定义会写入请求并
 * 计入容量估算，顺序变化会改变字节数与计量结果。
 */
public final class OpenAiChatToolAdapter {

    private OpenAiChatToolAdapter() {
    }

    public static List<Map<String, Object>> tools(List<ToolCapability> capabilities) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCapability capability : capabilities) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", capability.name());
            function.put("description", capability.description());
            function.put("parameters", parameters(capability));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            tools.add(tool);
        }
        return List.copyOf(tools);
    }

    private static Map<String, Object> parameters(ToolCapability capability) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (ToolParameter parameter : capability.parameters()) {
            properties.put(parameter.name(), schema(parameter));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("properties", properties);
        parameters.put("required", capability.requiredParameters());
        return parameters;
    }

    private static Map<String, Object> schema(ToolParameter parameter) {
        if (parameter instanceof ToolParameter.IntegerValue integer) {
            return integerSchema(integer);
        }
        if (parameter instanceof ToolParameter.Array array) {
            return arraySchema(array);
        }
        if (parameter instanceof ToolParameter.Object object) {
            return objectSchema(object);
        }
        return textSchema((ToolParameter.Text) parameter);
    }

    private static Map<String, Object> textSchema(ToolParameter.Text text) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (text.role() == ToolParameter.Role.QUERY) {
            schema.put("maxLength", text.maxChars());
            schema.put("type", "string");
            schema.put("minLength", text.minLength());
            schema.put("description", text.description());
            return schema;
        }
        if (!text.enumValues().isEmpty()) {
            schema.put("description", text.description());
            schema.put("type", "string");
            schema.put("enum", text.enumValues());
            return schema;
        }
        if (text.pattern() != null) {
            if (text.description() != null) {
                schema.put("description", text.description());
                schema.put("type", "string");
                schema.put("pattern", text.pattern());
            } else {
                schema.put("pattern", text.pattern());
                schema.put("type", "string");
            }
            return schema;
        }
        if (text.description() != null) {
            schema.put("description", text.description());
            schema.put("type", "string");
            if (text.maxChars() != null) {
                schema.put("maxLength", text.maxChars());
            }
            return schema;
        }
        schema.put("maxLength", text.maxChars());
        schema.put("type", "string");
        return schema;
    }

    private static Map<String, Object> integerSchema(ToolParameter.IntegerValue integer) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (integer.description() != null && integer.defaultValue() != null) {
            schema.put("type", "integer");
            schema.put("description", integer.description());
            putBound(schema, "maximum", integer.maximum());
            putBound(schema, "minimum", integer.minimum());
            schema.put("default", integer.defaultValue());
        } else if (integer.description() != null && integer.maximum() != null) {
            schema.put("type", "integer");
            schema.put("maximum", integer.maximum());
            putBound(schema, "minimum", integer.minimum());
            schema.put("description", integer.description());
        } else {
            if (integer.description() != null) {
                schema.put("description", integer.description());
            }
            putBound(schema, "minimum", integer.minimum());
            schema.put("type", "integer");
        }
        return schema;
    }

    private static void putBound(Map<String, Object> schema, String field, Integer value) {
        if (value != null) {
            schema.put(field, value);
        }
    }

    private static Map<String, Object> arraySchema(ToolParameter.Array array) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", schema(array.items()));
        if (array.description() != null) {
            schema.put("description", array.description());
        }
        putBound(schema, "maxItems", array.maxItems());
        return schema;
    }

    private static Map<String, Object> objectSchema(ToolParameter.Object object) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter property : object.properties()) {
            properties.put(property.name(), schema(property));
            if (property.required()) {
                required.add(property.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.copyOf(required));
        schema.put("properties", properties);
        return schema;
    }
}
