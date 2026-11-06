package com.codereview.agent.prompt;
import com.codereview.agent.invocation.*;
import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.codereview.agent.tools.ToolLimitProperties;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Frozen before PAR-03; expectations must not be regenerated after refactoring. */
class PromptRefactorBaselineTest {
 @Test void frozenRequestsAndCapacityRemainEquivalent() throws Exception {
  var mapper = new ObjectMapper(); var assembly = new PromptAssemblyService();
  Map<String,AssembledPrompt> cases = new LinkedHashMap<>();
  for (var purpose : PromptPurpose.values()) {
   // ISSUE_PR 是 PRS-03 新增用途，不写入 PAR 冻结请求；正文由 PullRequestPromptTest 单独核对。
   if (purpose == PromptPurpose.AGENT || purpose == PromptPurpose.AGENT_TOOL_CHECK
           || purpose == PromptPurpose.ISSUE_PR) continue;
   for (boolean rich : List.of(false,true)) {
    var m = new PromptMaterial("M1","src/中文.java","head","AFTER",7,8,"class A {}\n// 证据\n","COMPLETE",null);
    cases.put(purpose + (rich ? "-rich" : "-minimal"),assembly.assemble(new PromptInput(purpose,"1",
     rich ? "规则" : null,rich ? "检查边界\n保留限制" : null,rich ? "注".repeat(4001) : null,
     "DIFF","dev","head","base",purpose.isSpecialist()?List.of(m):List.of(),rich?List.of(m):List.of(),
     rich?List.of("依赖缺失"):List.of(),rich?List.of(new PromptFact("S1","冻结事实\n数量=1")):List.of(),
     rich?new PromptInput.FormatRetry(1,"PROTOCOL_INVALID","错".repeat(2001)):null,
     rich?"报告标题":null,rich?"说明边界":null)));
   }
  }
  var tools=AgentToolCapabilities.readOnly(new ToolLimitProperties());
  var call=new ToolCall("call-1","read_code","{\"version\":\"V1\",\"path\":\"src/A.java\"}");
  for(String scope:List.of("TEXT","FILES","COMMITS")) {
   var input=new AgentPromptInput("检查重试",scope,"dev","head","范围",List.of("src/A.java"),
    List.of(new AgentPromptInput.Version("V1","head")),List.of(new AgentPromptInput.Comparison("C1","head",null),new AgentPromptInput.Comparison("C2","head2","base")),"指导","旧结果");
   cases.put("agent-"+scope,assembly.assembleAgent(input,List.of(),tools));
   cases.put("agent-"+scope+"-followup",assembly.assembleAgent(input,List.of(PromptMessage.assistantToolCalls(List.of(call),null),PromptMessage.toolResult(call.id(),"{\"status\":\"OK\"}")),tools));
  }
  cases.put("tool-check",assembly.assembleToolCheck(tools));
  cases.put("tool-check-followup",assembly.assembleToolCheckFollowUp(tools,call,"第1至3行"));
  var props=new ModelInvocationProperties();var capacity=new ModelCapacityService(props);
  var invocation=new ModelInvocationService(null,mapper,null,capacity,assembly,null,props,null);
  var actual=mapper.createObjectNode();
  for(var entry:cases.entrySet()) {
   var model=new ModelSnapshot("fixture","fixture","https://invalid.example","deepseek-chat","not-used",65536,16384,null,"OPENAI_CHAT",1);
   var prompt=entry.getValue();var estimate=capacity.estimate(prompt,model);var node=actual.putObject(entry.getKey());
   node.set("assembled",mapper.valueToTree(prompt));node.set("capacity",mapper.valueToTree(estimate));
   String body=ReflectionTestUtils.invokeMethod(invocation,"requestBody",model,prompt,estimate.outputReserve());
   node.set("request",mapper.readTree(body));
  }
  Path baseline=Path.of("src/test/resources/prompt-baseline/requests.json");
  if(Boolean.getBoolean("par.captureBaseline")) {
   assertFalse(Files.exists(baseline),"Refuse to overwrite frozen baseline");Files.createDirectories(baseline.getParent());
   Files.writeString(baseline,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual)+"\n");
  }
  assertEquals(mapper.readTree(Files.readString(baseline)),actual,"Do not refresh expectations after refactoring");
 }
}
