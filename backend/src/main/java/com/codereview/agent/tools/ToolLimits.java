package com.codereview.agent.tools;

/** 只读工具的资源上限；数值来自 P33.1 预算实验，改变时同步 schema 版本。 */
public final class ToolLimits {

    /** 单次工具返回的 JSON 总字节上限（UTF-8）。 */
    public static final int ENVELOPE_MAX_BYTES = 32 * 1024;
    /** 单条材料正文上限（UTF-8）。 */
    public static final int MATERIAL_MAX_BYTES = 24 * 1024;
    /** 单条命中行的显示上限（字符），截短必须显式标记。 */
    public static final int HIT_LINE_MAX_CHARS = 1000;

    public static final int TREE_DEFAULT_DEPTH = 2;
    public static final int TREE_MAX_DEPTH = 3;
    public static final int TREE_DEFAULT_LIMIT = 100;
    public static final int TREE_MAX_LIMIT = 200;

    public static final int FIND_DEFAULT_LIMIT = 50;
    public static final int FIND_MAX_LIMIT = 100;
    public static final int GLOB_MAX_CHARS = 256;

    public static final int SEARCH_DEFAULT_LIMIT = 50;
    public static final int SEARCH_MAX_LIMIT = 100;
    public static final int SEARCH_QUERY_MAX_CHARS = 256;
    public static final int SEARCH_CONTEXT_LINES = 2;
    /** 单次 grep 最多缓存多少条命中；达到上限即标记截断，不宣称全量。 */
    public static final int SEARCH_GREP_MAX_HITS = 200;
    public static final int SEARCH_MAX_EXTENSIONS = 10;
    /** 搜索等待槽位加执行的总时限（秒）。 */
    public static final int SEARCH_TOTAL_SECONDS = 10;
    /** 搜索全局并发；任务内本身串行执行工具。 */
    public static final int SEARCH_GLOBAL_CONCURRENCY = 2;

    public static final int READ_DEFAULT_LINES = 200;
    public static final int READ_MAX_LINES = 400;

    public static final int DIFF_MAX_ITEMS = 20;
    public static final int DIFF_MAX_HUNKS = 10;

    public static final int CURSOR_MAX_CHARS = 1024;
    public static final int PATH_MAX_CHARS = 512;

    private ToolLimits() {
    }
}
