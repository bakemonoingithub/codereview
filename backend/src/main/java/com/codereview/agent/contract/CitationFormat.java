package com.codereview.agent.contract;

import java.util.regex.Pattern;

/** 引用编号和正文标记共用前缀；扫描器保留非法长编号，以便业务层诊断。 */
public record CitationFormat(String prefix, Integer maxDigits) {
    public CitationFormat {
        if (prefix == null || !prefix.matches("[A-Za-z]+") || maxDigits != null && maxDigits < 1) {
            throw new IllegalArgumentException("非法引用格式");
        }
    }
    public String id(int ordinal) { return prefix + ordinal; }
    public String marker(int ordinal) { return "[" + id(ordinal) + "]"; }
    public String idPattern() {
        return prefix + (maxDigits == null ? "[0-9]+" : "[0-9]{1," + maxDigits + "}");
    }
    public String schemaPattern() {
        return "^" + idPattern() + "$";
    }
    public Pattern markerPattern() { return Pattern.compile("\\[" + Pattern.quote(prefix) + "(\\d+)]"); }
}
