package com.codereview.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffUnitBuilderTest {

    private static final String NEW_FILE =
            "public class A {\n"
                    + "    public void m() {\n"
                    + "        int a = 1;\n"
                    + "        int b = 2;\n"
                    + "        int c = 3;\n"
                    + "    }\n"
                    + "}\n";

    private static final String PATCH =
            "@@ -1,6 +1,7 @@\n"
                    + " public class A {\n"
                    + "     public void m() {\n"
                    + "         int a = 1;\n"
                    + "+        int b = 2;\n"
                    + "         int c = 3;\n"
                    + "     }\n"
                    + " }\n";

    private static DiffUnitBuilder builder() {
        return new DiffUnitBuilder(60, 140_000);
    }

    // ---------------- 方法级补全（主路径） ----------------

    @Test
    void methodLevelCompletionRendersNewSideWithLineNumbersAndMarkers() {
        List<DiffReviewUnit> units = builder().buildFromPatch("src/A.java", "modified", PATCH, NEW_FILE);

        assertEquals(1, units.size());
        DiffReviewUnit u = units.get(0);
        assertEquals("diff-method", u.kind());
        assertEquals("m", u.name(), "应定位到 hunk 所属方法");
        assertEquals(2, u.startLine());
        assertEquals(6, u.endLine());
        assertFalse(u.truncated());
        assertTrue(u.text().contains("==== 方法: m"), u.text());

        String added = lineContaining(u, "int b = 2;");
        assertTrue(added.startsWith("+"), "新增行应带 + 标记：" + added);
        assertTrue(added.contains("4 |"), "新增行应带新侧行号：" + added);

        String context = lineContaining(u, "int a = 1;");
        assertTrue(context.startsWith(" "), "上下文行不带 +/-：" + context);
        assertTrue(context.contains("3 |"), context);
    }

    @Test
    void removedLineIsRenderedWithoutNewSideNumber() {
        String newFile = "public class A {\n"
                + "    public void m() {\n"
                + "        int a = 1;\n"
                + "    }\n"
                + "}\n";
        String patch = "@@ -1,6 +1,5 @@\n"
                + " public class A {\n"
                + "     public void m() {\n"
                + "         int a = 1;\n"
                + "-        int b = 2;\n"
                + "     }\n"
                + " }\n";

        DiffReviewUnit u = builder().buildFromPatch("A.java", "modified", patch, newFile).get(0);

        String removed = lineContaining(u, "int b = 2;");
        assertTrue(removed.contains("-"), "删除行应带 - 标记：" + removed);
        assertFalse(removed.matches(".*\\d+ \\|.*"), "删除行在新侧已不存在，不应有行号：" + removed);
    }

    // ---------------- 退化 1：非 Java / 解析失败 ----------------

    @Test
    void nonJavaFileFallsBackToRawHunkWithNote() {
        String patch = "@@ -1,3 +1,4 @@\n SELECT 1;\n+SELECT 2;\n SELECT 3;\n";

        List<DiffReviewUnit> units = builder().buildFromPatch("db/x.sql", "modified", patch, null);

        assertEquals(1, units.size());
        assertEquals("diff-hunk", units.get(0).kind());
        assertTrue(units.get(0).note().contains("回退原始 hunk"), units.get(0).note());
        assertTrue(units.get(0).text().contains("@@ -1,3 +1,4 @@"));
    }

    @Test
    void unparsableJavaContentAlsoFallsBackToRawHunk() {
        List<DiffReviewUnit> units = builder().buildFromPatch("A.java", "modified", PATCH, "this is not java {{{");

        assertEquals(1, units.size());
        assertEquals("diff-hunk", units.get(0).kind());
    }

    @Test
    void changeOutsideAnyMethodBecomesRawHunkBlock() {
        String newFile = "import java.util.List;\n\npublic class A {\n}\n";
        String patch = "@@ -1 +1 @@\n-import java.util.Set;\n+import java.util.List;\n";

        DiffReviewUnit u = builder().buildFromPatch("A.java", "modified", patch, newFile).get(0);

        assertEquals("diff-hunk", u.kind(), "方法外的改动（如 import）无法方法级补全，应回退 hunk");
    }

    // ---------------- 退化 2：超长方法体窗口 ----------------

    @Test
    void overlongMethodIsWindowedAndMarked() {
        StringBuilder sb = new StringBuilder("public class Big {\n    public void m() {\n");
        for (int lineNo = 3; lineNo <= 62; lineNo++) {
            int value = (lineNo == 32) ? 320 : lineNo;
            sb.append("        int v").append(lineNo).append(" = ").append(value).append(";\n");
        }
        sb.append("    }\n}\n");
        String patch = "@@ -31,3 +31,3 @@\n"
                + "         int v31 = 31;\n"
                + "-        int v32 = 32;\n"
                + "+        int v32 = 320;\n"
                + "         int v33 = 33;\n";

        DiffReviewUnit u = new DiffUnitBuilder(10, 140_000)
                .buildFromPatch("A.java", "modified", patch, sb.toString()).get(0);

        assertTrue(u.truncated(), "方法体 62 行 > 窗口 10，应标注截断");
        assertTrue(u.text().contains("仅展示变更行"), u.text());
        assertTrue(u.text().contains("int v32 = 320;"), "变更行必须在窗口内");
        assertFalse(u.text().contains("int v62 = 62;"), "窗口外的行不应出现");
    }

    // ---------------- 退化 3：patch 不可用 → 调用方走全文件 ----------------

    @Test
    void emptyOrUnparsablePatchReturnsEmptySoCallerCanFallBack() {
        DiffUnitBuilder b = builder();

        assertTrue(b.buildFromPatch("A.java", "modified", null, NEW_FILE).isEmpty());
        assertTrue(b.buildFromPatch("A.java", "modified", "   ", NEW_FILE).isEmpty());
        assertTrue(b.buildFromPatch("A.java", "modified", "no hunk here\n", NEW_FILE).isEmpty());
    }

    @Test
    void fullFileFallbackNumbersEveryLine() {
        DiffReviewUnit u = builder().buildFromFullFile("a/b/Thing.sql", "modified", "SELECT 1;\nSELECT 2;");

        assertEquals("full-file", u.kind());
        assertEquals("Thing.sql", u.name());
        assertEquals(1, u.startLine());
        assertEquals(2, u.endLine());
        assertTrue(u.text().contains("SELECT 1;"));
        assertTrue(u.note().contains("完整文件"), u.note());
    }

    // ---------------- 组装：一文件一单元 / 超阈值按块拆 ----------------

    @Test
    void overThresholdSplitsIntoOneUnitPerBlock() {
        String content = twoMethodContent();
        String patch = "@@ -13 +13 @@\n"
                + "-        int alpha10 = 10;\n"
                + "+        int alpha10 = 110;\n"
                + "@@ -45 +45 @@\n"
                + "-        int beta10 = 10;\n"
                + "+        int beta10 = 110;\n";

        List<DiffReviewUnit> merged = new DiffUnitBuilder(60, 140_000)
                .buildFromPatch("Two.java", "modified", patch, content);
        assertEquals(1, merged.size(), "默认一个变更文件 = 一个单元");
        assertEquals("diff-method", merged.get(0).kind());

        List<DiffReviewUnit> split = new DiffUnitBuilder(60, 1_000)
                .buildFromPatch("Two.java", "modified", patch, content);
        assertEquals(2, split.size(), "超过 maxChars 后应按块拆成多个单元");
        assertTrue(split.get(0).text().contains("alpha10 = 110"));
        assertTrue(split.get(1).text().contains("beta10 = 110"));
    }

    private static String twoMethodContent() {
        StringBuilder sb = new StringBuilder("public class Two {\n");
        sb.append("    public void a() {\n");
        for (int i = 0; i < 30; i++) {
            int value = (i == 10) ? 110 : i;
            sb.append("        int alpha").append(i).append(" = ").append(value).append(";\n");
        }
        sb.append("    }\n");
        sb.append("    public void b() {\n");
        for (int i = 0; i < 30; i++) {
            int value = (i == 10) ? 110 : i;
            sb.append("        int beta").append(i).append(" = ").append(value).append(";\n");
        }
        sb.append("    }\n}\n");
        return sb.toString();
    }

    private static String lineContaining(DiffReviewUnit unit, String needle) {
        return unit.text().lines().filter(l -> l.contains(needle)).findFirst().orElse("");
    }
}
