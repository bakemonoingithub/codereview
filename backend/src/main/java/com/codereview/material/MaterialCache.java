package com.codereview.material;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 共享物料内存缓存：按「commitSha + 范围」复用，满足同一 commit 二次分析更快。
 */
@Component
public class MaterialCache {

    private final Map<String, Material> cache = new ConcurrentHashMap<>();

    public Material getOrCompute(String key, Supplier<Material> supplier) {
        return cache.computeIfAbsent(key, k -> supplier.get());
    }

    public static String key(String commitSha, List<String> scope) {
        List<String> sorted = new ArrayList<>(scope);
        Collections.sort(sorted);
        return (commitSha == null || commitSha.isBlank() ? "HEAD" : commitSha) + "|" + String.join(",", sorted);
    }
}
