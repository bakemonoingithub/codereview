package com.codereview.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐行文本 diff（LCS 最长公共子序列），用于提示词版本对比。
 */
public final class TextDiff {

    private TextDiff() {
    }

    /** 一行差异：type = same | add | remove；oldLine/newLine 为对应侧行号（不存在为 null）。 */
    public record DiffLine(String type, String text, Integer oldLine, Integer newLine) {
    }

    public static List<DiffLine> diff(String oldText, String newText) {
        String[] a = oldText == null ? new String[0] : oldText.split("\n", -1);
        String[] b = newText == null ? new String[0] : newText.split("\n", -1);
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<DiffLine> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        int oldLine = 1;
        int newLine = 1;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.add(new DiffLine("same", a[i], oldLine++, newLine++));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new DiffLine("remove", a[i], oldLine++, null));
                i++;
            } else {
                out.add(new DiffLine("add", b[j], null, newLine++));
                j++;
            }
        }
        while (i < n) {
            out.add(new DiffLine("remove", a[i++], oldLine++, null));
        }
        while (j < m) {
            out.add(new DiffLine("add", b[j++], null, newLine++));
        }
        return out;
    }
}
