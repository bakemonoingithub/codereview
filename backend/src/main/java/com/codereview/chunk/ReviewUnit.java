package com.codereview.chunk;

/**
 * 审查单元：切分后的最小可审查单位，带固定头信息 {文件路径, 类型, 名称, 行范围}。
 */
public record ReviewUnit(String path, String kind, String name, int startLine, int endLine, String code) {

    /** 注入提示词用的固定头。 */
    public String header() {
        return String.format("{文件路径:%s, 类型:%s, 名称:%s, 行范围:%d-%d}", path, kind, name, startLine, endLine);
    }
}
