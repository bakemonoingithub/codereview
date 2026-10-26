package com.codereview.agent.tools;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@Component
@ConfigurationProperties(prefix = "agent.tools.limits")
public class ToolLimitProperties {
    private int envelopeMaxBytes = ToolLimits.ENVELOPE_MAX_BYTES;
    private int materialMaxBytes = ToolLimits.MATERIAL_MAX_BYTES;
    private int treeDefaultDepth = ToolLimits.TREE_DEFAULT_DEPTH;
    private int treeMaxDepth = ToolLimits.TREE_MAX_DEPTH;
    private int treeDefaultLimit = ToolLimits.TREE_DEFAULT_LIMIT;
    private int treeMaxLimit = ToolLimits.TREE_MAX_LIMIT;
    private int findDefaultLimit = ToolLimits.FIND_DEFAULT_LIMIT;
    private int findMaxLimit = ToolLimits.FIND_MAX_LIMIT;
    private int searchDefaultLimit = ToolLimits.SEARCH_DEFAULT_LIMIT;
    private int searchMaxLimit = ToolLimits.SEARCH_MAX_LIMIT;
    private int searchContextLines = ToolLimits.SEARCH_CONTEXT_LINES;
    private int searchGrepMaxHits = ToolLimits.SEARCH_GREP_MAX_HITS;
    private int readDefaultLines = ToolLimits.READ_DEFAULT_LINES;
    private int readMaxLines = ToolLimits.READ_MAX_LINES;
    private int diffMaxItems = ToolLimits.DIFF_MAX_ITEMS;
    private int diffMaxHunks = ToolLimits.DIFF_MAX_HUNKS;
    private int grepOutputMaxBytes = GitGrepRunner.MAX_OUTPUT_BYTES;
}
