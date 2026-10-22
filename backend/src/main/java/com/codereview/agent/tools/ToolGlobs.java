package com.codereview.agent.tools;

import java.util.regex.Pattern;

/**
 * 文件路径 glob：只支持 {@code *}（单段内任意）、{@code **}（跨目录）与 {@code ?}。
 *
 * <p>不做 shell 花括号展开，不把用户输入放进正则前先做转义；
 * 大小写按仓库实际路径，不自动忽略大小写。
 */
public final class ToolGlobs {

    private ToolGlobs() {
    }

    public static boolean matches(String glob, String path) {
        return Pattern.compile(toRegex(glob)).matcher(path).matches();
    }

    static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (doubleStar) {
                    index += 2;
                    // "**/" 同时匹配零层或多层目录，避免 src/**/*.java 漏掉 src/A.java
                    if (index < glob.length() && glob.charAt(index) == '/') {
                        index++;
                        if (index == glob.length()) {
                            regex.append(".*");
                            break;
                        }
                        boolean atSegmentStart = regex.length() == 1 || regex.charAt(regex.length() - 1) == '/';
                        regex.append(atSegmentStart ? "(?:.*/)?" : ".*");
                    } else {
                        regex.append(".*");
                    }
                    continue;
                }
                regex.append("[^/]*");
                index++;
                continue;
            }
            if (current == '?') {
                regex.append("[^/]");
                index++;
                continue;
            }
            regex.append(Pattern.quote(String.valueOf(current)));
            index++;
        }
        return regex.append('$').toString();
    }
}
