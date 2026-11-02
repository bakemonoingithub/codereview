package com.codereview.agent.prompt;

import com.codereview.agent.prompt.template.PromptTemplateRegistry;
import com.codereview.agent.tools.capability.ToolCapability;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一的提示词组装门面：调用方传入类型化输入，不自行拼接消息。
 *
 * <p>场景编排委托策略，公共格式与计量由共享组件维护。构造时完整加载并校验模板，
 * 无参手工装配与 Spring 装配使用相同组件；契约关联见 BRC maintenance-map。
 */
@Service
public class PromptAssemblyService {
    public static final String ADAPTER_VERSION = "openai-chat-v1";
    public static final String ESTIMATE_METHOD = "ESTIMATED_UTF8_BYTES_V1";

    private final Map<PromptPurpose, PromptInputStrategy> strategies;
    private final PromptSystemRenderer systems;
    private final AgentPromptStrategy agent;
    private final ToolCheckPromptStrategy toolCheck;
    private final AgentCorrectionRenderer corrections;

    public PromptAssemblyService() {
        PromptTemplateRegistry templates = PromptTemplateRegistry.loadDefault();
        systems = new PromptSystemRenderer(templates);
        PromptSections sections = new PromptSections(templates);
        StandardPromptStrategy standard = new StandardPromptStrategy(systems, sections);
        SpecialistPromptStrategy specialist = new SpecialistPromptStrategy(standard);
        ReportPromptStrategy report = new ReportPromptStrategy(systems, sections);
        EnumMap<PromptPurpose, PromptInputStrategy> routes = new EnumMap<>(PromptPurpose.class);
        routes.put(PromptPurpose.ISSUE_FILE, specialist);
        routes.put(PromptPurpose.ISSUE_DIFF, specialist);
        routes.put(PromptPurpose.PATTERN, specialist);
        routes.put(PromptPurpose.COUPLING, specialist);
        routes.put(PromptPurpose.REPORT, report);
        routes.put(PromptPurpose.VALIDATION, standard);
        // 保留原 assemble(PromptInput) 对这两种用途的普通消息行为；业务仍走专用入口。
        routes.put(PromptPurpose.AGENT, standard);
        routes.put(PromptPurpose.AGENT_TOOL_CHECK, standard);
        if (routes.size() != PromptPurpose.values().length) {
            throw new IllegalStateException("存在未注册的提示词用途");
        }
        strategies = Map.copyOf(routes);
        agent = new AgentPromptStrategy(templates, systems);
        toolCheck = new ToolCheckPromptStrategy(templates, systems);
        corrections = new AgentCorrectionRenderer(templates);
    }

    public AssembledPrompt assemble(PromptInput input) {
        return strategies.get(input.purpose()).assemble(input);
    }

    public AssembledPrompt assembleAgent(AgentPromptInput input, List<PromptMessage> history,
                                         List<ToolCapability> capabilities) {
        return agent.assembleAgent(input, history, capabilities);
    }

    public AssembledPrompt assembleToolCheck(List<ToolCapability> capabilities) {
        return toolCheck.assembleToolCheck(capabilities);
    }

    public AssembledPrompt assembleToolCheckFollowUp(List<ToolCapability> capabilities,
                                                     ToolCall call, String toolResult) {
        return toolCheck.assembleToolCheckFollowUp(capabilities, call, toolResult);
    }

    public String correction(AgentCorrection event) {
        return corrections.correction(event);
    }

    public String systemPrompt(PromptPurpose purpose) {
        return systems.systemPrompt(purpose);
    }

    public String protocolBody(PromptPurpose purpose) {
        return systems.protocolBody(purpose);
    }

    // 保留包内辅助入口；格式与计量实现各只维护一处。
    static String numberLines(String content, int startLine) {
        return PromptSections.numberLines(content, startLine);
    }

    static String bound(String text, int maxChars) {
        return PromptSections.bound(text, maxChars);
    }

    static int utf8Bytes(String text) {
        return PromptMessageFactory.utf8Bytes(text);
    }

    static int toolsBytes(List<Map<String, Object>> tools) {
        return PromptMessageFactory.toolsBytes(tools);
    }
}
