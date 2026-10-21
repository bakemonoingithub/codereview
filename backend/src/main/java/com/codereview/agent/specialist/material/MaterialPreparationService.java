package com.codereview.agent.specialist.material;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.repository.RepositoryPaths;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.SpecialistTypes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Gap;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PrepareCommand;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PreparedTarget;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Role;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Side;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Status;

/**
 * 逐文件材料准备：固定版本读取、结构解析、完整性记录和目标/辅助区分。
 *
 * <p>边界：
 * <ul>
 *   <li>文件目标读取失败、二进制或超过 4 MiB 时，该目标直接失败，不返回空材料；</li>
 *   <li>问题与模式专项遇到非 Java 或解析失败，仍可用原文分析，但把缺口写进材料；</li>
 *   <li>耦合专项必须取得目标 AST，解析失败就是文件失败，不能显示成零依赖；</li>
 *   <li>提交差异保留 base/head、新旧路径与 BEFORE/AFTER，新增/删除文件只取存在的一侧。</li>
 * </ul>
 *
 * <p>准备结果先于模型调用产生；P31.4 的拆分和 P31.5 的执行都在这个结果上继续，
 * 不重新读取最新源码。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class MaterialPreparationService {

    private final RepositoryReadService repository;
    private final MaterialStore store;
    private final JavaStructureParser parser;

    public MaterialPreparationService(RepositoryReadService repository, MaterialStore store,
                                      JavaStructureParser parser) {
        this.repository = repository;
        this.store = store;
        this.parser = parser;
    }

    public PreparedTarget prepare(PrepareCommand command) {
        PrepareCommand normalized = validate(command);
        try {
            return "DIFF".equals(normalized.sourceKind())
                    ? prepareDiff(normalized) : prepareFile(normalized);
        } catch (ApiException failure) {
            return PreparedTarget.failure(normalized, failure.errorCode().code(), failure.getMessage());
        }
    }

    private PreparedTarget prepareFile(PrepareCommand command) {
        RepositorySnapshots.FileSnapshot snapshot = repository.readFile(
                command.projectId(), command.headSha(), command.path());
        List<Gap> gaps = new ArrayList<>();
        JavaSourceStructure structure = parse(command, snapshot.path(), snapshot.content(), gaps,
                SpecialistTypes.COUPLING.equals(command.specialistType()));
        addCoverageGap(command.path(), structure, gaps);
        Material material = save(new Material(AgentIds.next(), command.projectId(), snapshot.sha(),
                snapshot.path(), Side.FILE, 1, Math.max(1, snapshot.lineCount()), snapshot.content(),
                snapshot.contentHash(), "COMPLETE", null, Role.TARGET, Status.RESOLVED, null,
                structure));
        return new PreparedTarget(command.projectId(), command.specialistType(),
                command.sourceKind(), command.branch(), snapshot.path(), null, snapshot.sha(),
                null, command.note(), false, false, List.of(material), List.of(), gaps, null, null);
    }

    private PreparedTarget prepareDiff(PrepareCommand command) {
        RepositorySnapshots.DiffSides sides = repository.diff(
                command.projectId(), command.headSha(), command.path());
        if (sides.binary()) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE,
                    "二进制文件不可作为审查材料：" + sides.path(), Map.of("reason", "BINARY"));
        }
        boolean added = isAdded(sides);
        boolean removed = isRemoved(sides);
        List<Gap> gaps = new ArrayList<>();
        List<Material> materials = new ArrayList<>();

        if (sides.after().present()) {
            JavaSourceStructure structure = parse(command, sides.path(), sides.after().content(), gaps,
                    SpecialistTypes.COUPLING.equals(command.specialistType()));
            materials.add(save(diffMaterial(command, sides.path(), sides.headSha(), Side.AFTER,
                    sides.after().content(), structure)));
        } else {
            addMissingSide(gaps, "AFTER", sides.after(), removed);
        }
        if (sides.before().present()) {
            String beforePath = sides.oldPath() != null && !sides.oldPath().isBlank()
                    ? sides.oldPath() : sides.path();
            JavaSourceStructure structure = parse(command, beforePath, sides.before().content(), gaps,
                    !sides.after().present());
            materials.add(save(diffMaterial(command, beforePath, sides.baseSha(), Side.BEFORE,
                    sides.before().content(), structure)));
        } else {
            addMissingSide(gaps, "BEFORE", sides.before(), added);
            if ("ROOT_COMMIT".equals(sides.before().reason())) {
                gaps.add(new Gap("BASE_UNAVAILABLE", sides.path(),
                        "根提交没有父版本，差异只包含新增侧"));
            }
        }

        if (materials.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_NOT_READABLE,
                    "提交差异的两侧都没有可读正文：" + sides.path(),
                    Map.of("before", sides.before().status(), "after", sides.after().status()));
        }
        for (Material material : materials) {
            addCoverageGap(material.path(), material.structure(), gaps);
        }
        return new PreparedTarget(command.projectId(), command.specialistType(),
                command.sourceKind(), command.branch(), sides.path(), sides.oldPath(),
                sides.headSha(), sides.baseSha(), command.note(), added, removed,
                List.copyOf(materials), List.of(), gaps, null, null);
    }

    private Material diffMaterial(PrepareCommand command, String path, String sha, Side side,
                                  String content, JavaSourceStructure structure) {
        int lineCount = JavaStructureParser.countLines(content);
        return new Material(AgentIds.next(), command.projectId(), sha, path, side, 1,
                Math.max(1, lineCount), content, ContentHash.sha256(content), "COMPLETE", null,
                Role.TARGET, Status.RESOLVED, null, structure);
    }

    /**
     * 解析结构。非 Java 或解析失败时：耦合目标失败，问题/模式保留原文并记录缺口。
     */
    private JavaSourceStructure parse(PrepareCommand command, String path, String content,
                                      List<Gap> gaps, boolean structureRequired) {
        if (!path.toLowerCase(Locale.ROOT).endsWith(".java")) {
            if (structureRequired) {
                throw new ApiException(ErrorCode.UNSUPPORTED_LANGUAGE,
                        "耦合度分析只支持 Java 文件：" + path, Map.of("path", path));
            }
            gaps.add(new Gap(Status.UNSUPPORTED.toString(), path, "非 Java 文件，未做结构增强"));
            return null;
        }
        try {
            return parser.parse(path, content);
        } catch (StructureParseException failure) {
            if (structureRequired) {
                throw new ApiException(ErrorCode.STRUCTURE_PARSE_FAILED, failure.getMessage(),
                        Map.of("path", path, "reason", "PARSE_FAILED"));
            }
            gaps.add(new Gap(Status.PARSE_FAILED.toString(), path,
                    "结构解析失败，仅使用原文分析：" + failure.getMessage()));
            return null;
        }
    }

    private static void addCoverageGap(String path, JavaSourceStructure structure, List<Gap> gaps) {
        if (structure != null && !structure.uncoveredLines().isEmpty()) {
            gaps.add(SpecialistMaterials.incompleteCoverage(path, structure.uncoveredLines()));
        }
    }

    private static void addMissingSide(List<Gap> gaps, String side,
                                       RepositorySnapshots.Side snapshot, boolean expectedMissing) {
        if (snapshot.status().equals(RepositorySnapshots.Side.UNREADABLE)) {
            gaps.add(new Gap(Status.NOT_AVAILABLE.toString(), side,
                    "该侧存在但读取失败：" + snapshot.reason()));
        } else if (!expectedMissing) {
            gaps.add(new Gap(Status.NOT_AVAILABLE.toString(), side,
                    "该侧不存在：" + snapshot.reason()));
        }
    }

    private static boolean isAdded(RepositorySnapshots.DiffSides sides) {
        return "added".equalsIgnoreCase(sides.status())
                || ("MISSING".equals(sides.before().status()) && "ADDED".equals(sides.before().reason()));
    }

    private static boolean isRemoved(RepositorySnapshots.DiffSides sides) {
        return "removed".equalsIgnoreCase(sides.status())
                || ("MISSING".equals(sides.after().status()) && "REMOVED".equals(sides.after().reason()));
    }

    private Material save(Material material) {
        return store.save(material);
    }

    private static PrepareCommand validate(PrepareCommand command) {
        if (command == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少材料准备参数");
        }
        String type = command.specialistType() == null
                ? "" : command.specialistType().trim().toUpperCase(Locale.ROOT);
        if (!SpecialistTypes.isValid(type)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "专项类型只能是 ISSUE、PATTERN 或 COUPLING",
                    Map.of("specialistType", command.specialistType()));
        }
        String kind = command.sourceKind() == null
                ? "" : command.sourceKind().trim().toUpperCase(Locale.ROOT);
        if (!"FILES".equals(kind) && !"DIFF".equals(kind)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "sourceKind 只能是 FILES 或 DIFF",
                    Map.of("sourceKind", command.sourceKind()));
        }
        String path = RepositoryPaths.requireSafePath(command.path());
        String oldPath = command.oldPath() == null || command.oldPath().isBlank()
                ? null : RepositoryPaths.requireSafePath(command.oldPath());
        String headSha = RepositoryPaths.requireFixedSha(command.headSha());
        String baseSha = command.baseSha() == null || command.baseSha().isBlank()
                ? null : RepositoryPaths.requireFixedSha(command.baseSha());
        if ("FILES".equals(kind) && baseSha != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "文件目标不应携带 baseSha");
        }
        if ("DIFF".equals(kind) && baseSha != null && baseSha.equals(headSha)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "比较基线与目标提交不能相同");
        }
        String branch = command.branch() == null || command.branch().isBlank()
                ? null : command.branch().trim();
        String note = command.note() == null || command.note().isBlank()
                ? null : command.note().trim();
        return new PrepareCommand(command.projectId(), type, kind, branch, path, oldPath, note,
                headSha, baseSha);
    }

}
