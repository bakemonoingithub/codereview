package com.codereview.agent.prompt;

import com.codereview.agent.tools.ToolLimitProperties;
import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent PAS-02 collection: identical inputs run separately against before and after. */
class Pas02DifferentialTest {
    @Test void collectCompleteOutputs() throws Exception {
        var a = new PromptAssemblyService();
        var mapper = new ObjectMapper();
        Map<String,Object> out = new LinkedHashMap<>();
        String[] optional = {null, "", " \r\n", " 规则{{原文}}😀 "};
        int n = 0;
        for (var purpose : PromptPurpose.values()) {
            for (String text : optional) {
                for (String content : List.of("", "中文😀", "中文😀\n", "中文\r\n末行")) {
                    var m = new PromptMaterial("M1", "中文.java", null, "AFTER", 9, null,
                            content, "COMPLETE", text);
                    var in = new PromptInput(purpose, " ", text, text, text, null, " ", "sha", null,
                            List.of(m), List.of(m), Arrays.asList("", " ", text == null ? "" : text),
                            List.of(new PromptFact("S1", "事实{{原文}}")), new PromptInput.FormatRetry(1,null,text),text,text);
                    out.put("optional-" + n++,a.assemble(in));
                }
            }
        }
        for (int delta : List.of(-1,0,1)) {
            var m = new PromptMaterial("M1","A.java","sha","FILE",1,1,"a", "COMPLETE","诊".repeat(500+delta));
            out.put("independent-boundary-"+delta,a.assemble(new PromptInput(PromptPurpose.ISSUE_FILE,"1",null,null,
                    "注".repeat(4000+delta),"FILES",null,"sha",null,List.of(m),List.of(),List.of("缺".repeat(1000+delta)),
                    List.of(),new PromptInput.FormatRetry(1,null,"错".repeat(2000+delta)))));
        }
        var limits = new ToolLimitProperties();
        limits.setReadMaxLines(17);
        limits.setTreeMaxDepth(2);
        var call = new ToolCall("c1","read_code","{\"path\":\"中文.java\"}");
        var call2 = new ToolCall("c2","tree","{}");
        List<List<PromptMessage>> histories = Arrays.asList(null,List.of(),List.of(
                PromptMessage.assistantToolCalls(List.of(call,call2),""),PromptMessage.toolResult("c1","😀\r\n"),
                PromptMessage.toolResult("c2","{}"),PromptMessage.assistantToolCalls(List.of(call),null),
                PromptMessage.toolResult("c1","{{末轮}}")));
        for (var tools : List.of(AgentToolCapabilities.readOnly(new ToolLimitProperties()),AgentToolCapabilities.agent(limits))) {
            out.put("tool-first-"+n++,a.assembleToolCheck(tools));
            out.put("tool-next-"+n++,a.assembleToolCheckFollowUp(tools,call,"{{工具结果}}😀"));
            for (String scope : List.of("TEXT","FILES","COMMITS")) {
                for (String text : optional) {
                    for (var paths : List.of(List.<String>of(),List.of("A.java","中文.java"))) {
                        for (var history : histories) {
                            var input = new AgentPromptInput(text,scope,null,"sha",text,paths,
                                    List.of(new AgentPromptInput.Version("V1","sha"),new AgentPromptInput.Version("V2","sha2")),
                                    text == null ? List.of() : List.of(new AgentPromptInput.Comparison("C1","sha",null),new AgentPromptInput.Comparison("C2","sha2","base")),text,text);
                            String prior = mapper.writeValueAsString(history);
                            out.put("agent-"+n++,a.assembleAgent(input,history,tools));
                            assertEquals(prior,mapper.writeValueAsString(history));
                        }
                    }
                }
            }
        }
        Files.writeString(Path.of("pas02-differential.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out)+"\n");
        assertEquals(279,out.size());
    }
}
