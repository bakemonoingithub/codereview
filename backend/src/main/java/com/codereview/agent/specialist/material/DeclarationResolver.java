package com.codereview.agent.specialist.material;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Status;

/**
 * 在同一固定版本内定位 Java 类型声明。
 *
 * <p>顺序按 Java 的可见性规则：文件内类型 → 显式 import → 同包 → 通配 import。
 * 找到候选文件后必须解析并核对 package 与实际声明；仅凭文件名或“列表第一个”不算定位。
 * 多个源码根或同名类型给出 AMBIGUOUS，第三方/仓库外类型给出 NOT_AVAILABLE，
 * 两者都不解释为“类型不存在”。
 *
 * <p>实例按 {@code (project, sha)} 创建；路径清单与已读文件只在该版本内复用。
 */
public final class DeclarationResolver {

    private final String projectId;
    private final String sha;
    private final RepositoryReadService repository;
    private final JavaStructureParser parser;
    private final ReadBudget readBudget;

    private RepositorySnapshots.PathList pathList;
    private Set<String> pathSet;
    private final Map<String, Loaded> files = new HashMap<>();

    public DeclarationResolver(String projectId, String sha, RepositoryReadService repository,
                               JavaStructureParser parser) {
        this(projectId, sha, repository, parser, new ReadBudget(Integer.MAX_VALUE, Long.MAX_VALUE));
    }

    DeclarationResolver(String projectId, String sha, RepositoryReadService repository,
                        JavaStructureParser parser, ReadBudget readBudget) {
        this.projectId = projectId;
        this.sha = sha;
        this.repository = repository;
        this.parser = parser;
        this.readBudget = readBudget;
    }

    public String sha() {
        return sha;
    }

    public Resolved resolve(String raw, JavaSourceStructure source) {
        TypeNames.TypeUse use = TypeNames.parse(raw);
        String base = use.base();
        if (base.isBlank() || "?".equals(base)) {
            return Resolved.internal(raw, "通配符或空类型");
        }
        if (TypeNames.isPrimitiveOrVoid(base)) {
            return Resolved.external(raw, "Java 基本类型");
        }
        if (source != null && source.declaresReference(base)) {
            return Resolved.internal(raw, "目标文件内已声明");
        }
        if (TypeNames.isJdkPlatform(base)) {
            return Resolved.external(raw, "Java 平台类型");
        }
        if (TypeNames.isProbablyQualified(base)) {
            return resolveFqcn(raw, base);
        }
        if (base.contains(".")) {
            int dot = base.indexOf('.');
            String head = base.substring(0, dot);
            String suffix = base.substring(dot);
            NameResolution outer = names(head, source);
            if (outer.status() == Status.EXTERNAL) {
                return Resolved.external(raw, outer.diagnostic());
            }
            if (outer.fqcns().isEmpty()) {
                return Resolved.of(outer.status(), raw, null, null, null, null, null,
                        outer.diagnostic());
            }
            List<Resolved> resolved = new ArrayList<>();
            for (String fqcn : outer.fqcns()) {
                resolved.add(resolveFqcn(raw, fqcn + suffix));
            }
            return one(resolved);
        }
        NameResolution names = names(base, source);
        if (names.status() == Status.EXTERNAL) {
            return Resolved.external(raw, names.diagnostic());
        }
        if (names.fqcns().isEmpty()) {
            return Resolved.of(names.status(), raw, null, null, null, null, null,
                    names.diagnostic());
        }
        List<Resolved> resolved = new ArrayList<>();
        for (String fqcn : names.fqcns()) {
            resolved.add(resolveFqcn(raw, fqcn));
        }
        return one(resolved);
    }

    /**
     * 把简单名或外部类型前缀解析成候选全限定名；只给出候选，不读取文件。
     */
    private NameResolution names(String name, JavaSourceStructure source) {
        String packageName = source == null ? "" : source.packageName();
        List<String> explicit = new ArrayList<>();
        List<String> wildcard = new ArrayList<>();
        if (source != null) {
            for (JavaSourceStructure.ImportInfo item : source.imports()) {
                if (item.isStatic()) {
                    continue;
                }
                if (item.wildcard()) {
                    wildcard.add(item.name());
                } else if (item.name().equals(name) || item.name().endsWith("." + name)) {
                    explicit.add(item.name());
                }
            }
        }
        if (!explicit.isEmpty()) {
            boolean platform = explicit.stream().allMatch(TypeNames::isJdkPlatform);
            return new NameResolution(explicit, platform ? Status.EXTERNAL : Status.RESOLVED,
                    platform ? "Java 平台类型" : null);
        }
        String samePackage = packageName.isBlank() ? name : packageName + "." + name;
        Resolved same = resolveFqcn(name, samePackage);
        if (same.status() == Status.RESOLVED || same.status() == Status.AMBIGUOUS
                || same.status() == Status.PARSE_FAILED || same.status() == Status.LIMIT_REACHED) {
            return new NameResolution(List.of(samePackage), same.status(), same.diagnostic());
        }
        List<String> candidates = new ArrayList<>();
        boolean platformWildcard = !wildcard.isEmpty();
        for (String prefix : wildcard) {
            platformWildcard &= TypeNames.isJdkPlatform(prefix);
            candidates.add(prefix + "." + name);
        }
        if (!candidates.isEmpty()) {
            return new NameResolution(candidates,
                    platformWildcard ? Status.EXTERNAL : Status.RESOLVED,
                    platformWildcard ? "Java 平台类型" : null);
        }
        if (TypeNames.isJdkBasic(name)) {
            return new NameResolution(List.of(), Status.EXTERNAL, "Java 平台类型");
        }
        return new NameResolution(List.of(), Status.NOT_AVAILABLE,
                "仓库路径清单没有可对应的源码，可能为第三方依赖或未提交文件");
    }

    private Resolved resolveFqcn(String requested, String fqcn) {
        List<Resolved> matches = new ArrayList<>();
        Loaded incomplete = null;
        for (String path : matchingPaths(fqcn)) {
            Loaded loaded = load(path);
            if (loaded.status() != Status.RESOLVED) {
                if (incomplete == null || loaded.status() == Status.LIMIT_REACHED) {
                    incomplete = loaded;
                }
                continue;
            }
            JavaSourceStructure.TypeDecl type = loaded.structure().type(fqcn).orElse(null);
            if (type == null) {
                continue;
            }
            matches.add(new Resolved(Status.RESOLVED, requested, fqcn, path, sha,
                    loaded.content(), loaded.structure(), type, null));
        }
        if (matches.size() > 1) {
            String detail = "同名类型有多个可核对声明：" + matches.stream()
                    .map(match -> match.path() + "#" + match.qualifiedName())
                    .reduce((left, right) -> left + ", " + right).orElse("");
            return Resolved.of(Status.AMBIGUOUS, requested, null, null, null, null, null, detail);
        }
        if (incomplete != null) {
            return Resolved.of(incomplete.status(), requested, null, null, null, null, null,
                    incomplete.diagnostic());
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        return Resolved.of(Status.NOT_AVAILABLE, requested, null, null, null, null, null,
                "未在版本 " + sha + " 中找到声明：" + fqcn);
    }

    private static Resolved one(List<Resolved> resolved) {
        // 未能核对全部候选时，不能把另一个可读候选当作唯一声明。
        for (Status status : List.of(Status.LIMIT_REACHED, Status.AMBIGUOUS, Status.PARSE_FAILED)) {
            for (Resolved candidate : resolved) {
                if (candidate.status() == status) return candidate;
            }
        }
        List<Resolved> hits = resolved.stream()
                .filter(item -> item.status() == Status.RESOLVED).toList();
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (hits.size() > 1) {
            String detail = "通配 import 或嵌套类型产生歧义：" + hits.stream()
                    .map(Resolved::qualifiedName).reduce((left, right) -> left + ", " + right)
                    .orElse("");
            return Resolved.of(Status.AMBIGUOUS, resolved.get(0).requested(), null, null, null,
                    null, null, detail);
        }
        for (Status status : List.of(Status.PARSE_FAILED, Status.AMBIGUOUS, Status.NOT_AVAILABLE)) {
            Resolved failed = resolved.stream().filter(item -> item.status() == status)
                    .findFirst().orElse(null);
            if (failed != null) {
                return failed;
            }
        }
        return resolved.get(0);
    }

    private Loaded load(String path) {
        return files.computeIfAbsent(path, key -> {
            if (!readBudget.reserveFile()) {
                return new Loaded(Status.LIMIT_REACHED, null, null, "辅助源码读取达到文件数或字节上限");
            }
            try {
                RepositorySnapshots.FileSnapshot snapshot = repository.readFile(projectId, sha, key);
                if (!readBudget.recordBytes(snapshot.content().getBytes(StandardCharsets.UTF_8).length)) {
                    return new Loaded(Status.LIMIT_REACHED, null, null,
                            "辅助源码全文超过剩余读取字节上限，未解析或发送，停止后续读取");
                }
                JavaSourceStructure structure = parser.parse(key, snapshot.content());
                return new Loaded(Status.RESOLVED, snapshot.content(), structure, null);
            } catch (StructureParseException failure) {
                return new Loaded(Status.PARSE_FAILED, null, null, failure.getMessage());
            } catch (ApiException failure) {
                if (failure.details() instanceof Map<?, ?> details
                        && details.get("byteSize") instanceof Number size
                        && !readBudget.recordBytes(size.longValue())) {
                    return new Loaded(Status.LIMIT_REACHED, null, null,
                            "候选源码超过读取字节上限，停止后续读取");
                }
                return new Loaded(Status.NOT_AVAILABLE, null, null,
                        failure.errorCode().code() + "：" + failure.getMessage());
            }
        });
    }

    private Set<String> pathSet() {
        if (pathSet == null) {
            pathList = repository.listPaths(projectId, sha);
            Set<String> paths = new LinkedHashSet<>();
            for (RepositorySnapshots.PathEntry entry : pathList.entries()) {
                if ("blob".equals(entry.type())) {
                    paths.add(entry.path());
                }
            }
            pathSet = paths;
        }
        return pathSet;
    }

    private static List<String> candidatePaths(String fqcn) {
        String[] parts = fqcn.split("\\.");
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (int length = parts.length; length >= 2; length--) {
            paths.add(String.join("/", Arrays.copyOf(parts, length)) + ".java");
        }
        paths.add(fqcn.replace('.', '/') + ".java");
        return List.copyOf(paths);
    }

    /** 固定版本路径按包路径后缀建立候选，不假定源码从仓库根开始。 */
    private List<String> matchingPaths(String fqcn) {
        Set<String> matches = new LinkedHashSet<>();
        for (String suffix : candidatePaths(fqcn)) {
            for (String path : pathSet()) {
                if (path.equals(suffix) || path.endsWith("/" + suffix)) matches.add(path);
            }
        }
        return List.copyOf(matches);
    }

    /** 跨版本解析器共用读取预算；失败候选也消耗次数和已取得的正文字节。 */
    static final class ReadBudget {
        private final int maxFiles;
        private final long maxBytes;
        private int files;
        private long bytes;

        ReadBudget(int maxFiles, long maxBytes) {
            this.maxFiles = Math.max(0, maxFiles);
            this.maxBytes = Math.max(0, maxBytes);
        }

        boolean reserveFile() {
            if (files >= maxFiles || bytes >= maxBytes) return false;
            files++;
            return true;
        }

        boolean recordBytes(long size) {
            bytes += size;
            return bytes <= maxBytes;
        }
    }

    private record NameResolution(List<String> fqcns, Status status, String diagnostic) {
        private NameResolution {
            fqcns = fqcns == null ? List.of() : List.copyOf(fqcns);
        }
    }

    private record Loaded(Status status, String content, JavaSourceStructure structure,
                          String diagnostic) {
    }

    /** 一次定位结果；{@code status} 不是 RESOLVED/INTERNAL 时正文与结构为空。 */
    public record Resolved(Status status, String requested, String qualifiedName, String path,
                           String sha, String content, JavaSourceStructure structure,
                           JavaSourceStructure.TypeDecl type, String diagnostic) {

        public static Resolved internal(String requested, String diagnostic) {
            return new Resolved(Status.INTERNAL, requested, null, null, null, null, null, null,
                    diagnostic);
        }

        public static Resolved external(String requested, String diagnostic) {
            return new Resolved(Status.EXTERNAL, requested, null, null, null, null, null, null,
                    diagnostic);
        }

        public static Resolved of(Status status, String requested, String qualifiedName,
                                  String path, String sha, String content,
                                  JavaSourceStructure structure, String diagnostic) {
            return new Resolved(status, requested, qualifiedName, path, sha, content, structure,
                    null, diagnostic);
        }

        public boolean resolved() {
            return status == Status.RESOLVED;
        }

        public boolean actionableGap() {
            return status != Status.RESOLVED && status != Status.INTERNAL
                    && status != Status.EXTERNAL;
        }

        public String displayName() {
            return qualifiedName == null ? requested : qualifiedName;
        }
    }
}
