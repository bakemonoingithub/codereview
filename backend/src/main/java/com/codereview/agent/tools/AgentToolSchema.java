package com.codereview.agent.tools;

import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.codereview.agent.tools.protocol.OpenAiChatToolAdapter;

import java.util.List;
import java.util.Map;

/**
 * 工具定义入口：能力定义与当前模型协议 JSON 的组装结果。
 *
 * <p>名称、说明与参数约束只在中立能力定义
 * {@link com.codereview.agent.tools.capability.AgentToolCapabilities} 维护；
 * 本类只保留调用方需要的静态入口与 schema 版本指纹。参数校验同样读取该能力定义，
 * 因此返回的 schema 与实际校验不会各自分叉。
 */
public final class AgentToolSchema {

    private AgentToolSchema() {
    }

    public static String version() {
        return version(new ToolLimitProperties());
    }

    public static String version(ToolLimitProperties limits) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .writeValueAsString(limits);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "agent-tools-v2-" + java.util.HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException("工具配置指纹失败", e); }
    }

    /** 五类只读工具；工具协议验证使用这一组定义。 */
    public static List<Map<String, Object>> definitions() {
        return definitions(new ToolLimitProperties());
    }

    public static List<Map<String, Object>> definitions(ToolLimitProperties limits) {
        return OpenAiChatToolAdapter.tools(AgentToolCapabilities.readOnly(limits));
    }

    /** Agent 执行使用的工具清单：五类只读工具 + 显式提交动作。 */
    public static List<Map<String, Object>> agentTools() {
        return agentTools(new ToolLimitProperties());
    }

    public static List<Map<String, Object>> agentTools(ToolLimitProperties limits) {
        return OpenAiChatToolAdapter.tools(AgentToolCapabilities.agent(limits));
    }
}
