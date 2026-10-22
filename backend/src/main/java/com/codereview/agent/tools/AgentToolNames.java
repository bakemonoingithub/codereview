package com.codereview.agent.tools;

import java.util.List;

/**
 * Agent 工具名与 schema 版本。
 *
 * <p>首版只注册五类只读工具；{@code submit_result} 是显式完成动作，由执行循环单独处理，
 * 不在这里当作材料读取工具，也不提供任何仓库写入工具。
 */
public final class AgentToolNames {

    public static final String TREE = "tree";
    public static final String FIND_FILES = "find_files";
    public static final String SEARCH_CODE = "search_code";
    public static final String READ_CODE = "read_code";
    public static final String READ_DIFF = "read_diff";
    public static final String SUBMIT_RESULT = "submit_result";

    /** 工具定义版本；模型准入与缓存键都带上它，schema 改变后旧验证自动失效。 */
    public static final String SCHEMA_VERSION = "agent-tools-v1";

    public static final List<String> READ_ONLY =
            List.of(TREE, FIND_FILES, SEARCH_CODE, READ_CODE, READ_DIFF);

    private AgentToolNames() {
    }

    public static boolean isReadOnly(String name) {
        return READ_ONLY.contains(name);
    }

    public static boolean isSubmit(String name) {
        return SUBMIT_RESULT.equals(name);
    }
}
