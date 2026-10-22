package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.git.GiteaProperties;
import com.codereview.git.GitMirror;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * {@code search_code}：在共享镜像上按字面量执行受限 {@code git grep}，返回可引用片段。
 *
 * <p>命中缓存按项目修订、SHA、完整查询、路径、后缀与 schema 版本隔离；
 * 失败与超时不写进缓存，避免把一次网络或进程问题记成"零命中"。
 * 搜索槽位等待与执行合计受 {@link ToolProperties#getSearchTotalSeconds()} 限制，镜像准备另计。
 */
@Component
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class SearchCodeTool {

    private final RepositoryReadService repository;
    private final ToolMaterialStore materials;
    private final GitMirror mirror;
    private final GitGrepRunner grep;
    private final ToolProperties properties;
    private final Semaphore slots;
    private final Cache<String, CachedSearch> cache;

    /** 生产装配使用共享镜像组件；测试与手工装配可用下一个构造自建。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SearchCodeTool(RepositoryReadService repository, ToolMaterialStore materials,
                          GitMirror mirror, GitGrepRunner grep, ToolProperties properties) {
        this.repository = repository;
        this.materials = materials;
        this.grep = grep;
        this.properties = properties;
        this.mirror = mirror;
        this.slots = new Semaphore(Math.max(1, properties.getSearchGlobalConcurrency()), true);
        this.cache = Caffeine.newBuilder()
                .maximumWeight(Math.max(1, properties.getSearchCacheWeightBytes()))
                .weigher((String key, CachedSearch value) -> Math.max(1, value.weight()))
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, properties.getSearchCacheMinutes())))
                .build();
    }

    /** 兼容既有测试与手工装配：按 Gitea 镜像配置自建共享镜像。 */
    public SearchCodeTool(RepositoryReadService repository, ToolMaterialStore materials,
                          GiteaProperties giteaProperties, GitGrepRunner grep,
                          ToolProperties properties) {
        this(repository, materials, new GitMirror(giteaProperties), grep, properties);
    }

    public ToolEnvelope execute(String toolCallId, ToolInvocation invocation, AgentToolScope scope) {
        RepositoryReadService.MirrorTarget target = repository.mirrorTarget(scope.projectId());
        List<String> extensions = normalizeExtensions(invocation.extensions());
        String key = target.namespace() + "|search|" + invocation.sha() + "|" + invocation.query()
                + "|" + invocation.path() + "|" + extensions + "|" + AgentToolNames.SCHEMA_VERSION;

        CachedSearch search = cache.getIfPresent(key);
        if (search == null) {
            long started = System.nanoTime();
            try {
                mirror.using(target.cloneUrl(), target.credential(), target.credentialType(),
                        List.of(invocation.sha()),
                        Duration.ofSeconds(properties.getMirrorTimeoutSeconds()),
                        dir -> dir);
            } catch (RuntimeException e) {
                return failure(toolCallId, scope, ToolEnvelope.UNREADABLE, "镜像准备失败：" + e.getMessage());
            }
            long remainingMillis = properties.getSearchTotalSeconds() * 1000L
                    - Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (remainingMillis <= 0) {
                return failure(toolCallId, scope, ToolEnvelope.TIMEOUT, "镜像准备已用尽搜索时限");
            }
            boolean acquired;
            try {
                acquired = slots.tryAcquire(remainingMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(toolCallId, scope, ToolEnvelope.TIMEOUT, "搜索等待被中断");
            }
            if (!acquired) {
                return failure(toolCallId, scope, ToolEnvelope.TIMEOUT, "等待搜索槽位超时");
            }
            try {
                long left = properties.getSearchTotalSeconds() * 1000L
                        - Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (left <= 0) {
                    return failure(toolCallId, scope, ToolEnvelope.TIMEOUT, "搜索已用尽时限");
                }
                GitGrepRunner.Result result = mirror.using(target.cloneUrl(), target.credential(),
                        target.credentialType(), List.of(invocation.sha()), Duration.ofSeconds(5),
                        (File dir) -> grep.search(dir, invocation.sha(), invocation.query(),
                                invocation.path(), Duration.ofMillis(left)));
                if (result.timedOut()) {
                    return failure(toolCallId, scope, ToolEnvelope.TIMEOUT,
                            result.diagnostic() == null ? "搜索超时" : result.diagnostic());
                }
                if (result.diagnostic() != null) {
                    return failure(toolCallId, scope, ToolEnvelope.ERROR, result.diagnostic());
                }
                List<GitGrepRunner.Hit> hits = result.hits();
                boolean truncated = result.truncated();
                if (hits.size() > ToolLimits.SEARCH_GREP_MAX_HITS) {
                    hits = hits.subList(0, ToolLimits.SEARCH_GREP_MAX_HITS);
                    truncated = true;
                }
                search = new CachedSearch(hits, truncated, null, weight(hits));
                cache.put(key, search);
            } finally {
                slots.release();
            }
        }

        List<GitGrepRunner.Hit> filtered = new ArrayList<>();
        for (GitGrepRunner.Hit hit : search.hits()) {
            if (matchesExtensions(hit.path(), extensions)) {
                filtered.add(hit);
            }
        }
        ToolCursor cursor = ToolCursor.decode(invocation.cursor(), invocation);
        int offset = Math.min(cursor.offset(), filtered.size());
        int limit = invocation.limit();

        List<Map<String, Object>> hits = new ArrayList<>();
        List<ToolEnvelope.ToolMaterial> materialsOut = new ArrayList<>();
        int budget = ToolLimits.ENVELOPE_MAX_BYTES - 512;
        int index = offset;
        int unreadable = 0;
        while (index < filtered.size() && hits.size() < limit) {
            GitGrepRunner.Hit hit = filtered.get(index);
            String lineText = ToolTexts.truncateLine(hit.text(), ToolLimits.HIT_LINE_MAX_CHARS);
            MaterialResult material = contextMaterial(scope, invocation.sha(), hit, lineText);
            if (!material.readable()) {
                unreadable++;
            }
            int cost = ToolTexts.utf8Bytes(material.material().content()) + lineText.length() + 200;
            if (!hits.isEmpty() && cost > budget) {
                break;
            }
            budget -= cost;
            ToolEnvelope.ToolMaterial saved = materials.save(scope.projectId(), material.material());
            hits.add(hitEntry(hit, lineText, saved.materialId()));
            materialsOut.add(saved);
            index++;
        }

        boolean hasMorePages = index < filtered.size();
        boolean unknownBeyond = search.truncated();
        String nextCursor = hasMorePages ? ToolCursor.encode(invocation, index) : null;
        String completeness = hasMorePages || unknownBeyond
                ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", invocation.query());
        if (invocation.path() != null) {
            data.put("path", invocation.path());
        }
        if (!extensions.isEmpty()) {
            data.put("extensions", extensions);
        }
        data.put("totalHits", filtered.size());
        data.put("hits", hits);

        List<String> notes = new ArrayList<>();
        if (unknownBeyond) {
            notes.add("grep 输出达到上限，只保留了前 " + ToolLimits.SEARCH_GREP_MAX_HITS + " 条原始命中");
        }
        if (unreadable > 0) {
            notes.add(unreadable + " 个命中文件无法读取上下文，只保留命中行");
        }
        if (hasMorePages) {
            notes.add("结果未返回完，可用 nextCursor 继续");
        }
        return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), null,
                materialsOut, data, completeness, nextCursor,
                notes.isEmpty() ? null : String.join("；", notes));
    }

    private MaterialResult contextMaterial(AgentToolScope scope, String sha, GitGrepRunner.Hit hit,
                                           String lineText) {
        try {
            RepositorySnapshots.FileSnapshot file = repository.readFile(scope.projectId(), sha, hit.path());
            String[] lines = ToolTexts.splitLines(file.content());
            if (hit.lineNumber() < 1 || hit.lineNumber() > lines.length) {
                return new MaterialResult(unreadableMaterial(sha, hit, lineText), false);
            }
            int start = Math.max(1, hit.lineNumber() - ToolLimits.SEARCH_CONTEXT_LINES);
            int end = Math.min(lines.length, hit.lineNumber() + ToolLimits.SEARCH_CONTEXT_LINES);
            String content = ToolTexts.joinLines(lines, start, end);
            // 先按行缩小窗口，使声明的行号与实际保存的正文一致；只有命中行本身超过字节上限时才截短。
            while (ToolTexts.utf8Bytes(content) > ToolLimits.MATERIAL_MAX_BYTES && end > start) {
                if (end > hit.lineNumber()) {
                    end--;
                } else if (start < hit.lineNumber()) {
                    start++;
                } else {
                    break;
                }
                content = ToolTexts.joinLines(lines, start, end);
            }
            String completeness = ToolEnvelope.COMPLETE;
            if (ToolTexts.utf8Bytes(content) > ToolLimits.MATERIAL_MAX_BYTES) {
                content = ToolTexts.clampToBytes(content, ToolLimits.MATERIAL_MAX_BYTES);
                completeness = ToolEnvelope.PARTIAL;
            }
            return new MaterialResult(new ToolEnvelope.ToolMaterial(null, hit.path(), "FILE", sha,
                    start, end, ToolTexts.sha256(content), completeness, content),
                    ToolEnvelope.COMPLETE.equals(completeness));
        } catch (ApiException e) {
            return new MaterialResult(unreadableMaterial(sha, hit, lineText), false);
        }
    }

    private static ToolEnvelope.ToolMaterial unreadableMaterial(String sha, GitGrepRunner.Hit hit,
                                                               String lineText) {
        return new ToolEnvelope.ToolMaterial(null, hit.path(), "FILE", sha, hit.lineNumber(),
                hit.lineNumber(), ToolTexts.sha256(lineText), ToolEnvelope.UNKNOWN, lineText);
    }

    private static Map<String, Object> hitEntry(GitGrepRunner.Hit hit, String lineText,
                                                String materialId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", hit.path());
        item.put("line", hit.lineNumber());
        item.put("text", lineText);
        item.put("materialId", materialId);
        return item;
    }

    private static List<String> normalizeExtensions(List<String> extensions) {
        List<String> normalized = new ArrayList<>();
        for (String extension : extensions) {
            String value = extension.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !value.startsWith(".")) {
                value = "." + value;
            }
            if (!value.isEmpty() && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static boolean matchesExtensions(String path, List<String> extensions) {
        if (extensions.isEmpty()) {
            return true;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(lower::endsWith);
    }

    private static int weight(List<GitGrepRunner.Hit> hits) {
        int weight = 64;
        for (GitGrepRunner.Hit hit : hits) {
            weight += hit.path().length() + hit.text().length() + 24;
        }
        return weight;
    }

    private static ToolEnvelope failure(String toolCallId, AgentToolScope scope, String status,
                                        String diagnostic) {
        return new ToolEnvelope(toolCallId, status, scope.versionRefs(), null, List.of(), null,
                ToolEnvelope.UNKNOWN, null, diagnostic);
    }

    /** 命中缓存；{@code truncated} 表示 grep 输出被截断，不能据此声称零命中或全量。 */
    record CachedSearch(List<GitGrepRunner.Hit> hits, boolean truncated, String diagnostic, int weight) {

        CachedSearch {
            hits = List.copyOf(hits);
        }
    }

    private record MaterialResult(ToolEnvelope.ToolMaterial material, boolean readable) {
    }
}
