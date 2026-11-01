package com.codereview.agent.tools.capability;

import com.codereview.agent.tools.AgentSubmissionContract;
import com.codereview.agent.tools.AgentToolNames;
import com.codereview.agent.tools.ToolLimitProperties;
import com.codereview.agent.tools.ToolLimits;

import java.util.List;

/**
 * Agent 工具描述提供层。
 *
 * <p>返回中立的能力定义：工具名称、用途说明、参数与约束。模型协议 JSON 由
 * {@code com.codereview.agent.tools.protocol.OpenAiChatToolAdapter} 生成；参数校验由
 * {@code ToolArgumentParser} 读取同一份参数定义执行。{@code submit_result} 的字段、
 * 长度与数量上限由 {@link AgentSubmissionContract} 统一声明，工具定义与提交解析共用。
 *
 * <p>数值约束来自 {@link ToolLimits} 与任务冻结的 {@link ToolLimitProperties}，
 * 与解析器、工具实现使用同一来源；取值范围变化会同时反映在描述与校验上。
 *
 * <p>维护关联 BRC-M04／M05：参数说明中的路径、查询、分页和完整性措辞由本类维护，
 * 语义校验在 ToolArgumentParser、ToolCursor 及各工具实现；修改时核对
 * tool-usage 模板、对应工具测试和 maintenance-map.md。
 */
public final class AgentToolCapabilities {

    static final String CURSOR_DESCRIPTION = "第一页省略 cursor；翻页只原样使用上一页返回的 nextCursor，"
            + "其他查询参数保持一致，包括本工具使用的 version/comparisonId、path、depth、limit、glob、query、extensions。"
            + "改变参数或切换清单／文件时，省略 cursor 从第一页重新开始；不要自行构造游标。";
    static final String EXTENSION_PATTERN = "^[.A-Za-z0-9_+-]{1,20}$";

    private AgentToolCapabilities() {
    }

    /** 五类只读工具；工具协议验证与 Agent 执行共用这一份定义。 */
    public static List<ToolCapability> readOnly(ToolLimitProperties limits) {
        return List.of(tree(limits), findFiles(limits), searchCode(limits), readCode(limits),
                readDiff(limits));
    }

    /** Agent 执行：五类只读工具加显式提交动作。 */
    public static List<ToolCapability> agent(ToolLimitProperties limits) {
        List<ToolCapability> tools = new java.util.ArrayList<>(readOnly(limits));
        tools.add(submitResult());
        return List.copyOf(tools);
    }

    private static ToolCapability tree(ToolLimitProperties limits) {
        return new ToolCapability(AgentToolNames.TREE,
                "列出固定版本下某一目录的直接条目（路径、类型、下级计数）。"
                        + "目录深度是请求范围，不代表已递归读遍所有文件。"
                        + "列仓库根目录时必须省略 path，例如 {\"version\":\"V1\"}；"
                        + "列子目录例如 {\"version\":\"V1\",\"path\":\"backend/src\"}。",
                List.of(
                        ToolParameter.versionRef("version", "固定版本标识，只能使用作用域给出的 V1…Vm"),
                        ToolParameter.path("path", false, relativePath(
                                "可选仓库内目录；省略 path 表示根目录，不要传空字符串或 \".\"。")),
                        ToolParameter.integer("depth", "展开层数，默认 " + limits.getTreeDefaultDepth(),
                                limits.getTreeDefaultDepth(), 1, limits.getTreeMaxDepth()),
                        ToolParameter.integer("limit", "最多返回条目数，默认 " + limits.getTreeDefaultLimit(),
                                limits.getTreeDefaultLimit(), 1, limits.getTreeMaxLimit()),
                        ToolParameter.cursor(CURSOR_DESCRIPTION)));
    }

    private static ToolCapability findFiles(ToolLimitProperties limits) {
        return new ToolCapability(AgentToolNames.FIND_FILES,
                "在固定版本中按 glob 匹配文件路径。支持 *、**、?，不做 shell 花括号展开，大小写按仓库实际路径。",
                List.of(
                        ToolParameter.versionRef("version", "固定版本标识"),
                        ToolParameter.glob("glob", true, "仓库内相对 glob，例如 src/**/*.java"),
                        ToolParameter.path("path", false, relativePath(
                                "可选目录前缀；查找全仓库时省略 path，不要传空字符串或 \".\"。")),
                        ToolParameter.integer("limit", "最多返回路径数，默认 " + limits.getFindDefaultLimit(),
                                limits.getFindDefaultLimit(), 1, limits.getFindMaxLimit()),
                        ToolParameter.cursor(CURSOR_DESCRIPTION)));
    }

    private static ToolCapability searchCode(ToolLimitProperties limits) {
        return new ToolCapability(AgentToolNames.SEARCH_CODE,
                "在固定版本中按字面量搜索代码（不解释正则），返回路径、原始行号、命中行及前后各 "
                        + limits.getSearchContextLines() + " 行。"
                        + "每个片段有独立材料 ID；零命中只有在完整性为 COMPLETE 时才说明确实不存在。",
                List.of(
                        ToolParameter.versionRef("version", "固定版本标识"),
                        ToolParameter.query("query", "非空、单行字面量搜索内容，不接受换行或回车，最多 "
                                + ToolLimits.SEARCH_QUERY_MAX_CHARS + " 字符"),
                        ToolParameter.path("path", false, relativePath(
                                "可选路径前缀；搜索全仓库时省略 path，不要传空字符串或 \".\"。")),
                        ToolParameter.array("extensions", false,
                                "至多 " + ToolLimits.SEARCH_MAX_EXTENSIONS + " 个后缀过滤，例如 .java",
                                ToolLimits.SEARCH_MAX_EXTENSIONS,
                                ToolParameter.pattern(null, false, null, EXTENSION_PATTERN)),
                        ToolParameter.integer("limit", "最多返回命中数，默认 " + limits.getSearchDefaultLimit(),
                                limits.getSearchDefaultLimit(), 1, limits.getSearchMaxLimit()),
                        ToolParameter.cursor(CURSOR_DESCRIPTION)));
    }

    private static ToolCapability readCode(ToolLimitProperties limits) {
        String materialLimit = limits.getMaterialMaxBytes() % 1024 == 0
                ? (limits.getMaterialMaxBytes() / 1024) + " KiB" : limits.getMaterialMaxBytes() + " 字节";
        return new ToolCapability(AgentToolNames.READ_CODE,
                "按原始行号读取固定版本的一段文件内容，返回内容哈希、总行数与下一页起点。"
                        + "一次最多 " + limits.getReadMaxLines() + " 行且正文最多 " + materialLimit
                        + "；从实际返回的末行+1 继续读。",
                List.of(
                        ToolParameter.versionRef("version", "固定版本标识"),
                        ToolParameter.path("path", true, relativePath(
                                "必填的具体文件路径，不能省略或传空字符串、\".\"。")),
                        ToolParameter.documentedInteger("start", "起始行，默认 1", 1, 1, 10_000_000),
                        new ToolParameter.IntegerValue("end", false,
                                "包含在读取范围内的结束行，不能小于起始行；省略时为 start+"
                                        + (limits.getReadDefaultLines() - 1),
                                null, null, 1, 10_000_000)));
    }

    private static ToolCapability readDiff(ToolLimitProperties limits) {
        return new ToolCapability(AgentToolNames.READ_DIFF,
                "读取一个冻结比较关系的变更清单，或指定路径的逐 hunk 前后侧内容。"
                        + "一次最多 " + limits.getDiffMaxItems() + " 个变更项或 " + limits.getDiffMaxHunks()
                        + " 个 hunk；分页不合并其他提交，前后侧均可引用。",
                List.of(
                        ToolParameter.comparisonRef("comparisonId", "比较关系标识，只能使用 C1…Cn"),
                        ToolParameter.path("path", false, relativePath(
                                "可选的变更清单中的规范新路径；省略 path 返回变更清单，不要传空字符串或 \".\"；切换文件时省略 cursor。")),
                        ToolParameter.cursor(CURSOR_DESCRIPTION)));
    }

    /** 显式提交动作：只在执行循环里暴露，不进入只读工具参数解析。 */
    public static ToolCapability submitResult() {
        return AgentSubmissionContract.tool();
    }

    private static String relativePath(String description) {
        return description
                + "提供路径时必须为非空仓库内相对路径，使用 / 分隔；禁止绝对路径、反斜杠以及 . 或 .. 路径段。";
    }
}
