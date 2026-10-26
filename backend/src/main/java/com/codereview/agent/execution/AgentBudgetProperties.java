package com.codereview.agent.execution;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@Component
@ConfigurationProperties(prefix = "agent.budget")
public class AgentBudgetProperties {
    private int modelTurns = AgentBudget.MODEL_TURNS;
    private int networkAttempts = AgentBudget.NETWORK_ATTEMPTS;
    private int toolAttempts = AgentBudget.TOOL_ATTEMPTS;
    private int deadlineMinutes = AgentBudget.DEADLINE_MINUTES;
    private int outputTokens = AgentBudget.OUTPUT_TOKENS;
    private int modelTimeoutSeconds = AgentBudget.MODEL_TIMEOUT_SECONDS;
    private double contextStopRatio = AgentBudget.CONTEXT_STOP_RATIO;
    private int noProgressTurns = AgentBudget.NO_PROGRESS_TURNS;
    private int maxToolsPerTurn = 4;
    private int maxProtocolErrors = 2;
}
