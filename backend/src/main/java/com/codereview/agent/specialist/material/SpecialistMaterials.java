package com.codereview.agent.specialist.material;

import com.codereview.agent.prompt.PromptMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.codereview.agent.specialist.material.JavaSourceStructure.LineRange;

/**
 * 专项材料准备的值对象。
 *
 * <p>材料分两类：{@link Role#TARGET} 是本次实际审查的目标；{@link Role#AUXILIARY}
 * 只是背景，模型不能把辅助材料当作新的问题目标。{@link Side} 保留文件目标和提交差异
 * 的前后侧，写结果和阅读时不能互换。
 */
public final class SpecialistMaterials {

    private SpecialistMaterials() {
    }

    public enum Side {
        FILE, BEFORE, AFTER;

        public boolean inDiff() {
            return this != FILE;
        }
    }

    public enum Role {
        TARGET, AUXILIARY
    }

    /**
     * 定位/读取结果。{@code RESOLVED} 与 {@code INTERNAL} 表示已定位，其余是缺口；
     * {@code NOT_AVAILABLE} 只表示“当前仓库版本没有可读源码”，不等于类型不存在。
     */
    public enum Status {
        RESOLVED, AMBIGUOUS, PARSE_FAILED, NOT_AVAILABLE, LIMIT_REACHED, EXTERNAL, INTERNAL, UNSUPPORTED
    }

    /**
     * 一条已保存或待保存的材料。
     *
     * <p>{@code id} 在准备阶段生成，写入数据库后保持不变，因此模型引用和
     * {@code call_material} 关联使用同一个稳定 ID。{@code structure} 只供执行期使用，
     * 不进入数据库正文。
     */
    public record Material(String id, String projectId, String sha, String path, Side side,
                           int startLine, int endLine, String content, String contentHash,
                           String completeness, String diagnostic, Role role, Status status,
                           String declaredType, JavaSourceStructure structure) {

        public Material {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("材料缺少稳定 ID");
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("材料缺少路径：" + id);
            }
            if (content == null) {
                throw new IllegalArgumentException("材料缺少正文：" + id);
            }
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("材料行范围非法：" + startLine + "-" + endLine);
            }
        }

        public Material withId(String newId) {
            return new Material(newId, projectId, sha, path, side, startLine, endLine, content,
                    contentHash, completeness, diagnostic, role, status, declaredType, structure);
        }

        public Material withDiagnostic(String newDiagnostic) {
            return new Material(id, projectId, sha, path, side, startLine, endLine, content,
                    contentHash, completeness, newDiagnostic, role, status, declaredType, structure);
        }

        public PromptMaterial toPromptMaterial() {
            return new PromptMaterial(id, path, sha, side.name(), startLine, endLine, content,
                    completeness, diagnostic);
        }

        public boolean target() {
            return role == Role.TARGET;
        }

        public boolean complete() {
            return "COMPLETE".equals(completeness);
        }
    }

    /** 一个可见的缺口；{@code code} 使用 {@link Status} 名称或结构解析错误码。 */
    public record Gap(String code, String subject, String detail) {

        public Gap {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("缺口缺少错误码");
            }
            subject = subject == null ? "" : subject;
            detail = detail == null ? "" : detail;
        }

        public String describe() {
            return code + "：" + (subject.isBlank() ? "" : subject + "，") + detail;
        }
    }

    /** 一次逐文件准备的输入；task/target 的持久化由执行层负责。 */
    public record PrepareCommand(String projectId, String specialistType, String sourceKind,
                                 String branch, String path, String oldPath, String note,
                                 String headSha, String baseSha) {
    }

    /**
     * 一个目标文件的准备结果。准备失败也返回对象而不是抛到任务级，
     * 这样批量执行时其他文件的结果不会因为一个文件读不到而丢失。
     */
    public record PreparedTarget(String projectId, String specialistType, String sourceKind,
                                 String branch, String path, String oldPath, String headSha,
                                 String baseSha, String note, boolean added, boolean removed,
                                 List<Material> targets, List<Material> auxiliary, List<Gap> gaps,
                                 String errorCode, String errorMessage) {

        public PreparedTarget {
            targets = targets == null ? List.of() : List.copyOf(targets);
            auxiliary = auxiliary == null ? List.of() : List.copyOf(auxiliary);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        public boolean failed() {
            return errorCode != null && !errorCode.isBlank();
        }

        public boolean javaTarget() {
            return targets.stream().anyMatch(material -> material.structure() != null);
        }

        public List<Material> allMaterials() {
            return Stream.concat(targets.stream(), auxiliary.stream()).toList();
        }

        public List<String> gapNotes() {
            List<String> notes = new ArrayList<>();
            for (Gap gap : gaps) {
                notes.add(gap.describe());
            }
            return List.copyOf(notes);
        }

        public PreparedTarget withAuxiliary(List<Material> materials, List<Gap> newGaps) {
            List<Gap> merged = new ArrayList<>(gaps);
            merged.addAll(newGaps);
            return new PreparedTarget(projectId, specialistType, sourceKind, branch, path, oldPath,
                    headSha, baseSha, note, added, removed, targets, materials, merged,
                    errorCode, errorMessage);
        }

        public static PreparedTarget failure(PrepareCommand command, String errorCode, String message) {
            return new PreparedTarget(command.projectId(), command.specialistType(),
                    command.sourceKind(), command.branch(), command.path(), command.oldPath(),
                    command.headSha(), command.baseSha(), command.note(), false, false,
                    List.of(), List.of(), List.of(), errorCode, message);
        }
    }

    /** 结构覆盖的缺口说明；A09 的“无漏行”由这里暴露，不静默吞掉。 */
    public static Gap incompleteCoverage(String path, List<Integer> uncoveredLines) {
        String lines = uncoveredLines.stream().limit(20).map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("");
        return new Gap("STRUCTURE_INCOMPLETE", path,
                "结构覆盖缺少非空行：" + lines
                        + (uncoveredLines.size() > 20 ? "（共 " + uncoveredLines.size() + " 行）" : ""));
    }

    static List<LineRange> rangesOf(JavaSourceStructure structure) {
        return structure == null ? List.of() : structure.coverage();
    }
}
