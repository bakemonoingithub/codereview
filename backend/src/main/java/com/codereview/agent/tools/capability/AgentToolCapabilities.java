package com.codereview.agent.tools.capability;

import com.codereview.agent.tools.AgentToolNames;
import com.codereview.agent.tools.AgentSubmission;
import com.codereview.agent.tools.ToolLimitProperties;
import com.codereview.agent.tools.ToolLimits;

import java.util.List;

/**
 * Agent 工具描述提供层。
 *
 * <p>返回中立的能力定义：工具名称、用途说明、参数与约束。模型协议 JSON 由
 * {@code com.codereview.agent.tools.protocol.OpenAiChatToolAdapter} 生成；参数校验由
 * {@code ToolArgumentParser} 读取同一份参数定义执行。文案只解释用法，不扩大执行权限。
 *
 * <p>数值约束来自 {@link ToolLimits} 与任务冻结的 {@link ToolLimitProperties}，
 * 与解析器、工具实现使用同一来源；取值范围变化会同时反映在描述与校验上。
 */
public final class AgentToolCapabilities {

    /** 仅演示提交结构；调用方必须替换材料 ID 和行号。 */
    public static final String ANSWERED_EXAMPLE = """
            {"title":"定位结果","summary":"结论由已读取材料支持。","answerStatus":"ANSWERED",
             "markdown":"结论由已读取材料支持。[E1]",
             "citations":[{"id":"E1","materialId":"<实际materialId>","startLine":12,"endLine":13}],
             "unresolved":[]}
            """;
    public static final String INSUFFICIENT_EVIDENCE_EXAMPLE = """
            {"title":"材料不足","summary":"尚缺关键实现材料。","answerStatus":"INSUFFICIENT_EVIDENCE",
             "markdown":"未取得关键实现，无法确认结论。","citations":[],
             "unresolved":["尚未读取到关键实现。"]}
            """;

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
        List<ToolParameter> citationProperties = List.of(
                ToolParameter.pattern("id", true,
                        "本次结果内唯一的引用编号，如 E1、E2，不带方括号；正文中写 [E1]、[E2]，不要使用 S1。",
                        "^E[0-9]{1,3}$"),
                ToolParameter.text("materialId", true,
                        "原样填写本次任务只读工具返回并已送入模型的材料 ID；不能编造，也不能从参考结果或其他任务复制。",
                        26),
                new ToolParameter.IntegerValue("startLine", true,
                        "引用起始行，包含端点，必须位于该 materialId 的实际返回行范围内。",
                        null, null, 1, null),
                new ToolParameter.IntegerValue("endLine", true,
                        "引用结束行，包含端点，不能小于 startLine，也不能超出该材料末行。"
                                + "不能把同一文件的多份材料范围拼到一个 materialId 上；需要更大范围时先调用 read_code。",
                        null, null, 1, null));
        return new ToolCapability(AgentToolNames.SUBMIT_RESULT,
                "结束调查并提交结果，必须单独调用，不能与其他工具同批。"
                        + "citations.id 使用 E1、E2，markdown 中必须写成 [E1]、[E2]，不能只填写引用数组。"
                        + "引用必须来自本次任务实际返回且已送入模型的材料，行号不得超出该 materialId 的返回范围。"
                        + "ANSWERED 至少一个正文实际使用的有效引用；INSUFFICIENT_EVIDENCE 必须填写非空 unresolved。"
                        + "以下示例仅演示格式，materialId 占位符和行号必须替换为本次实际材料值，不能照抄："
                        + ANSWERED_EXAMPLE
                        + "未取得证据时的提交示例：" + INSUFFICIENT_EVIDENCE_EXAMPLE,
                List.of(
                        ToolParameter.text("title", true, null, 200),
                        ToolParameter.text("summary", true, null, 1000),
                        ToolParameter.enumText("answerStatus", true,
                                "ANSWERED 要求至少一项有效引用且正文实际使用；INSUFFICIENT_EVIDENCE 要求 unresolved 非空，可无引用。",
                                List.of(AgentSubmission.ANSWERED, AgentSubmission.INSUFFICIENT_EVIDENCE)),
                        ToolParameter.text("markdown", true,
                                "结果正文；引用写在正文中，必须使用英文半角方括号，如：结论。[E1]。"
                                        + "多项写作 [E1][E2]，不能写成（E1）、见 E1 或 [E1,E2]；每个标记必须对应 citations 中有效的同名 id。",
                                24000),
                        ToolParameter.array("citations", false,
                                "引用定义；ANSWERED 必须提供至少一项有效定义，并在 markdown 中以 [E1] 等标记实际引用。只填数组不算正文引用；正文未使用的定义不会发布。",
                                100, ToolParameter.object(null, false, null, citationProperties)),
                        ToolParameter.array("unresolved", false,
                                "具体缺失材料或无法确认的问题；INSUFFICIENT_EVIDENCE 至少填写一项非空文本，无未解决事项时填空数组。",
                                20, ToolParameter.text(null, false, null, 1000))));
    }

    private static String relativePath(String description) {
        return description
                + "提供路径时必须为非空仓库内相对路径，使用 / 分隔；禁止绝对路径、反斜杠以及 . 或 .. 路径段。";
    }
}
