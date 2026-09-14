package com.codereview.common;

import com.codereview.git.ChangedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可审查白名单 —— 这是"该不该把文件交给分析器"的**唯一判定点**。
 *
 * <p>它此前不存在：判断散在前端（一份补不全的二进制扩展名表）而后端来者不拒，
 * 结果是二进制文件的内容会被整段送进大模型。
 */
class ReviewableFilesTest {

    @Test
    void allowsCommonCodeAndTextFiles() {
        assertTrue(ReviewableFiles.isReviewable("src/main/java/A.java"));
        assertTrue(ReviewableFiles.isReviewable("web/src/App.vue"));
        assertTrue(ReviewableFiles.isReviewable("pom.xml"));
        assertTrue(ReviewableFiles.isReviewable("conf/app.yml"));
        assertTrue(ReviewableFiles.isReviewable("README.md"));
        assertTrue(ReviewableFiles.isReviewable("db/schema.sql"));
    }

    /**
     * 这些正是旧前端那份黑名单漏掉的类型 —— 白名单的意义就在这里：
     * 不认识的一律不放行，而不是等发现了再补。
     */
    @Test
    void rejectsBinariesThatTheOldBlacklistMissed() {
        assertFalse(ReviewableFiles.isReviewable("bin/tool.exe"));
        assertFalse(ReviewableFiles.isReviewable("lib/native.dll"));
        assertFalse(ReviewableFiles.isReviewable("lib/libfoo.so"));
        assertFalse(ReviewableFiles.isReviewable("lib/libfoo.dylib"));
        assertFalse(ReviewableFiles.isReviewable("out/app.bin"));
        assertFalse(ReviewableFiles.isReviewable("pkg/archive.7z"));
        assertFalse(ReviewableFiles.isReviewable("pkg/archive.rar"));
        assertFalse(ReviewableFiles.isReviewable("sound/alert.wav"));
        assertFalse(ReviewableFiles.isReviewable("db/cache.sqlite"));
        // 旧名单里有的，仍然拦得住
        assertFalse(ReviewableFiles.isReviewable("assets/logo.png"));
        assertFalse(ReviewableFiles.isReviewable("lib/x.jar"));
    }

    /** 无扩展名的文件按不可审查处理 —— 刻意口径，避免"看似文本就放行"。 */
    @Test
    void rejectsFilesWithoutExtension() {
        assertFalse(ReviewableFiles.isReviewable("Makefile"));
        assertFalse(ReviewableFiles.isReviewable("Dockerfile"));
        assertFalse(ReviewableFiles.isReviewable("LICENSE"));
        // 点号开头不算扩展名
        assertFalse(ReviewableFiles.isReviewable(".gitignore"));
        // 目录路径同理
        assertFalse(ReviewableFiles.isReviewable("src/main/java"));
    }

    @Test
    void extensionMatchIsCaseInsensitive() {
        assertTrue(ReviewableFiles.isReviewable("SRC/Main.JAVA"));
        assertTrue(ReviewableFiles.isReviewable("pom.XML"));
    }

    /** 只看最后一段的扩展名，带点的目录名不能干扰判定。 */
    @Test
    void onlyLastSegmentMatters() {
        assertFalse(ReviewableFiles.isReviewable("com.example/logo.png"));
        assertTrue(ReviewableFiles.isReviewable("com.example/A.java"));
    }

    @Test
    void nullAndEmptyAreNotReviewable() {
        assertFalse(ReviewableFiles.isReviewable(null));
        assertFalse(ReviewableFiles.isReviewable(""));
        assertEquals("", ReviewableFiles.extensionOf(null));
    }

    @Test
    void reviewableOnlyKeepsOriginalOrder() {
        List<String> mixed = List.of("a.java", "logo.png", "b.xml", "tool.exe", "c.md");

        assertEquals(List.of("a.java", "b.xml", "c.md"), ReviewableFiles.reviewableOnly(mixed));
        assertEquals(List.of(), ReviewableFiles.reviewableOnly(null));
    }

    /**
     * {@link ChangedFile} 的 {@code reviewable} 由路径推导（紧凑构造器强制覆盖），
     * 调用方无法传错 —— 它不是独立事实，只是 path 的函数。
     */
    @Test
    void changedFileDerivesReviewableFromPath() {
        assertTrue(new ChangedFile("src/A.java", null, "modified", 1, 1, 2, "@@").reviewable());
        assertFalse(new ChangedFile("assets/logo.png", null, "modified", null, null, null, null).reviewable());
        // 即使调用方硬塞一个相反的值，也以路径为准
        assertFalse(new ChangedFile("assets/logo.png", null, "modified", null, null, null, null, true)
                .reviewable());
        // 剥离 patch 后 reviewable 不变
        assertTrue(new ChangedFile("src/A.java", null, "modified", 1, 1, 2, "@@").withoutPatch().reviewable());
    }
}
