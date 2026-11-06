package com.codereview.agent.prompt;

import com.codereview.agent.prompt.template.PromptTemplateRegistry;
import com.codereview.agent.report.ReportCitationContract;
import com.codereview.agent.specialist.protocol.SpecialistOutputContracts;
import com.codereview.agent.specialist.protocol.SpecialistProtocol;
import com.codereview.agent.tools.AgentSubmissionContract;
import java.util.LinkedHashMap;
import java.util.Map;

/** 系统指令与协议片段渲染；字段与语义维护关联见 BRC maintenance-map，契约仍为权威。 */
final class PromptSystemRenderer {
    private final PromptTemplateRegistry templates;

    PromptSystemRenderer(PromptTemplateRegistry templates) {
        this.templates = templates;
    }

    public String systemPrompt(PromptPurpose purpose) {
        String instruction = templates.render("instruction." + purpose.wireName(),
                purpose == PromptPurpose.REPORT
                        ? Map.of("citationRequirement", ReportCitationContract.citationRequirement())
                        : SpecialistOutputContracts.instructionValues(purpose));
        String protocol = protocolSuffix(purpose);
        String templateId = switch (purpose) {
            case REPORT -> "system.REPORT";
            case AGENT -> "system.AGENT";
            case AGENT_TOOL_CHECK -> "system.AGENT_TOOL_CHECK";
            default -> "system.SPECIALIST";
        };
        Map<String, String> values = new LinkedHashMap<>();
        values.put("instruction", instruction);
        values.put("protocol", protocol);
        if (purpose == PromptPurpose.REPORT) {
            values.put("citationMarker", ReportCitationContract.MARKER_EXAMPLE);
        }
        return templates.render(templateId, values);
    }

    public String protocolBody(PromptPurpose purpose) {
        String templateId = "protocol." + purpose.wireName();
        return switch (purpose) {
            case ISSUE_FILE, ISSUE_DIFF, ISSUE_PR, PATTERN, COUPLING -> templates.render(templateId,
                    Map.of("schema", SpecialistOutputContracts.forPurpose(purpose).protocolBlock()));
            case REPORT -> templates.render(templateId,
                    Map.of("citationMarker", ReportCitationContract.MARKER_EXAMPLE));
            case AGENT -> templates.render(templateId, AgentSubmissionContract.protocolValues());
            default -> templates.require(templateId).body();
        };
    }

    private String protocolSuffix(PromptPurpose purpose) {
        return templates.render("protocol.suffix", Map.of(
                "protocolVersion", SpecialistProtocol.VERSION,
                "purpose", purpose.wireName(),
                "schema", protocolBody(purpose)));
    }
}
