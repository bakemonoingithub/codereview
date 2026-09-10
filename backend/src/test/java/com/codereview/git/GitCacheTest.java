package com.codereview.git;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缓存层单测：不可变引用判定、按 key 命中、clear 生效。 */
class GitCacheTest {

    private static GitCache newCache() {
        return new GitCache(16, 16, 30);
    }

    private static CommitDetail detail(String sha) {
        return new CommitDetail(sha, List.of("p1"), "msg", "author", "date", 1, 1, 2, false, List.of());
    }

    // ---------------- 不可变引用判定 ----------------

    @Test
    void immutableRefAcceptsShaOnly() {
        assertTrue(GitCache.isImmutableRef("a1b2c3d"));
        assertTrue(GitCache.isImmutableRef("0123456789abcdef0123456789abcdef01234567"));
        assertTrue(GitCache.isImmutableRef("  a1b2c3d  "));

        assertFalse(GitCache.isImmutableRef("main"));
        assertFalse(GitCache.isImmutableRef("feature/x"));
        assertFalse(GitCache.isImmutableRef("abc"), "太短，不像 sha");
        assertFalse(GitCache.isImmutableRef("xyz1234"), "非十六进制");
        assertFalse(GitCache.isImmutableRef(null));
        assertFalse(GitCache.isImmutableRef(""));
    }

    @Test
    void keyScopesByOwnerRepoAndRef() {
        assertEquals("o/r@sha1", GitCache.key("o", "r", "sha1"));
    }

    // ---------------- 命中行为 ----------------

    @Test
    void commitDetailLoadsOnceThenHitsCache() {
        GitCache cache = newCache();
        AtomicInteger loads = new AtomicInteger();

        CommitDetail first = cache.commitDetail("o/r@sha", () -> {
            loads.incrementAndGet();
            return detail("sha");
        });
        CommitDetail second = cache.commitDetail("o/r@sha", () -> {
            loads.incrementAndGet();
            return detail("sha");
        });

        assertEquals(1, loads.get(), "第二次应命中缓存，不再回源");
        assertSame(first, second);
    }

    @Test
    void rawFileLoadsOnceThenHitsCacheAndIsolatesKeys() {
        GitCache cache = newCache();
        AtomicInteger loads = new AtomicInteger();

        String a1 = cache.rawFile("o/r@sha:a/A.java", () -> {
            loads.incrementAndGet();
            return "A";
        });
        String a2 = cache.rawFile("o/r@sha:a/A.java", () -> {
            loads.incrementAndGet();
            return "A";
        });
        String b = cache.rawFile("o/r@sha:b/B.java", () -> {
            loads.incrementAndGet();
            return "B";
        });

        assertEquals("A", a1);
        assertEquals("A", a2);
        assertEquals("B", b);
        assertEquals(2, loads.get(), "同 key 命中一次，不同 key 各回源一次");
    }

    @Test
    void changedFilesLoadsOnceThenHitsCache() {
        GitCache cache = newCache();
        AtomicInteger loads = new AtomicInteger();

        cache.changedFiles("o/r@b...h", () -> {
            loads.incrementAndGet();
            return List.of("a/A.java");
        });
        cache.changedFiles("o/r@b...h", () -> {
            loads.incrementAndGet();
            return List.of("a/A.java");
        });

        assertEquals(1, loads.get());
    }

    @Test
    void clearEmptiesAllCaches() {
        GitCache cache = newCache();
        AtomicInteger loads = new AtomicInteger();

        cache.rawFile("k", () -> {
            loads.incrementAndGet();
            return "v";
        });
        cache.commitDetail("c", () -> {
            loads.incrementAndGet();
            return detail("sha");
        });
        cache.changedFiles("f", () -> {
            loads.incrementAndGet();
            return List.of();
        });
        cache.clear();
        cache.rawFile("k", () -> {
            loads.incrementAndGet();
            return "v";
        });
        cache.commitDetail("c", () -> {
            loads.incrementAndGet();
            return detail("sha");
        });
        cache.changedFiles("f", () -> {
            loads.incrementAndGet();
            return List.of();
        });

        assertEquals(6, loads.get(), "clear 后三类缓存都应重新回源");
    }
}
