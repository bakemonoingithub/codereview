package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.invocation.InvocationRequest;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.InvocationScope;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.ToolCall;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生工具协议准入：用本地 fixture 完成两轮真实调用。
 *
 * <p>第 1 轮检查模型是否按 schema 发起工具调用（参数经与正式执行同一套解析器校验）；
 * 第 2 轮回放 assistant 工具调用与 tool 结果，要求模型给出最终文本。
 * 不使用仓库数据、不读取项目，也不把专项 JSON 探针当作工具能力证明。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentToolLoopValidator {

    public static final String SCOPE = "AGENT_TOOL_LOOP_V1";
    static final String FIXTURE_SHA = "0000000000000000000000000000000000000001";
    static final String FIXTURE_PATH = "fixture/Fixture.java";
    private static final String FIXTURE_CONTENT = """
            public class Fixture {
                void ping() {
                }
            }""";

    private final ModelInvocationService invocation;
    private final PromptAssemblyService assembly;
    private final ObjectMapper mapper;

    private ToolLimitProperties limits = new ToolLimitProperties();
    private com.codereview.agent.execution.AgentBudgetProperties budget = new com.codereview.agent.execution.AgentBudgetProperties();
    @org.springframework.beans.factory.annotation.Autowired
    public AgentToolLoopValidator(ModelInvocationService invocation, PromptAssemblyService assembly,
                                  ObjectMapper mapper, ToolLimitProperties limits,
                                  com.codereview.agent.execution.AgentBudgetProperties budget) {
        this(invocation, assembly, mapper); this.limits = limits; this.budget = budget;
    }
    public AgentToolLoopValidator(ModelInvocationService invocation,
                                  PromptAssemblyService assembly, ObjectMapper mapper) {
        this.invocation = invocation;
        this.assembly = assembly;
        this.mapper = mapper;
    }

    public String currentOptionsHash(ModelSnapshot model) { return optionsHash(model, limits, budget); }

    public Outcome validate(ModelSnapshot model) {
        List<Map<String, Object>> tools = AgentToolSchema.definitions(limits);
        List<String> callIds = new ArrayList<>();
        try {
            InvocationResult first = invoke(model, assembly.assembleToolCheck(tools), callIds);
            if (first.toolCalls().isEmpty()) {
                return failure("第 1 轮没有返回工具调用", callIds, model);
            }
            if (first.toolCalls().size() > budget.getMaxToolsPerTurn()) {
                return failure("第 1 轮工具调用超过 " + budget.getMaxToolsPerTurn() + " 个", callIds, model);
            }
            ToolCall primary = first.toolCalls().get(0);
            if (!AgentToolNames.isReadOnly(primary.name())) {
                return failure("工具名不在只读集合：" + primary.name(), callIds, model);
            }
            ToolInvocation invocation;
            try {
                invocation = new ToolArgumentParser(mapper).parse(primary.name(), primary.arguments(),
                        new AgentToolScope("fixture", Map.of("V1", FIXTURE_SHA), Map.of(), limits));
            } catch (ToolFailure e) {
                return failure("工具参数未通过校验：" + e.getMessage(), callIds, model);
            }

            String toolResult = fixtureResult(primary, invocation);
            InvocationResult second = invoke(model,
                    assembly.assembleToolCheckFollowUp(tools, primary, toolResult), callIds);
            if (second.content() == null || second.content().isBlank()) {
                return failure("第 2 轮没有返回最终文本", callIds, model);
            }
            String detail = "双轮工具协议通过：第 1 轮调用 " + primary.name()
                    + "，第 2 轮返回 " + second.content().strip().length() + " 字符";
            return new Outcome(true, detail, callIds, PromptAssemblyService.ADAPTER_VERSION,
                    AgentToolSchema.version(limits), optionsHash(model, limits, budget));
        } catch (ApiException e) {
            return failure("调用失败：" + e.getMessage(), callIds, model);
        }
    }

    private InvocationResult invoke(ModelSnapshot model, com.codereview.agent.prompt.AssembledPrompt prompt,
                                    List<String> callIds) {
        InvocationResult result = invocation.invoke(new InvocationRequest(model, prompt,
                InvocationScope.standalone(AgentTime.now().plusMinutes(2)), false, null, 1024));
        callIds.add(result.callId());
        return result;
    }

    /** 本地 fixture 的工具返回：结构、材料与正式工具一致，只是内容固定且不读仓库。 */
    private String fixtureResult(ToolCall call, ToolInvocation parsed) {
        String contentHash = sha256(FIXTURE_CONTENT);
        ToolEnvelope.ToolMaterial material = new ToolEnvelope.ToolMaterial(AgentIds.next(),
                parsed.path() == null ? FIXTURE_PATH : parsed.path(), "FILE", FIXTURE_SHA, 1, 3,
                contentHash, ToolEnvelope.COMPLETE, FIXTURE_CONTENT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", material.path());
        data.put("startLine", 1);
        data.put("endLine", 3);
        data.put("contentHash", contentHash);
        ToolEnvelope envelope = new ToolEnvelope(call.id(), ToolEnvelope.OK,
                List.of(new ToolEnvelope.VersionRef("V1", FIXTURE_SHA)), null,
                List.of(material), data, ToolEnvelope.COMPLETE, null, null);
        return ToolEnvelopes.writeString(mapper, envelope);
    }

    private Outcome failure(String detail, List<String> callIds, ModelSnapshot model) {
        return new Outcome(false, detail, List.copyOf(callIds),
                PromptAssemblyService.ADAPTER_VERSION, AgentToolSchema.version(limits), optionsHash(model, limits, budget));
    }

    /** 绑定模型修订、适配器、工具 schema 与本次选项；任一变化都应重新验证。 */
    public static String optionsHash(ModelSnapshot model) {
        return optionsHash(model, new ToolLimitProperties(), new com.codereview.agent.execution.AgentBudgetProperties());
    }
    public static String optionsHash(ModelSnapshot model, ToolLimitProperties limits,
                                     com.codereview.agent.execution.AgentBudgetProperties budget) {
        return sha256("revision=" + model.revision()
                + "|model=" + model.modelName()
                + "|adapter=" + PromptAssemblyService.ADAPTER_VERSION
                + "|tools=" + AgentToolSchema.version(limits)
                + "|maxTools=" + budget.getMaxToolsPerTurn() + "|protocolErrors=" + budget.getMaxProtocolErrors()
                + "|reasoning_effort=none|tool_choice=auto");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 验证结果；失败也返回对象，由调用方记录为一次成功的验证动作。 */
    public record Outcome(boolean passed, String detail, List<String> callIds,
                          String adapterVersion, String toolSchemaVersion, String optionsHash) {

        public Outcome {
            callIds = callIds == null ? List.of() : List.copyOf(callIds);
        }
    }
}
