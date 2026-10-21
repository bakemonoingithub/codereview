package com.codereview.agent.specialist.capacity;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.invocation.CapacityEstimate;
import com.codereview.agent.invocation.ModelCapacityService;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.specialist.SpecialistProperties;
import com.codereview.agent.specialist.coupling.CouplingFacts;
import com.codereview.agent.specialist.material.ContentHash;
import com.codereview.agent.specialist.material.JavaSourceStructure;
import com.codereview.agent.specialist.material.MaterialStore;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistPromptBuilder;
import com.codereview.agent.support.AgentIds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Gap;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PreparedTarget;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Role;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Status;

/**
 * 整文件优先的容量规划与授权拆分。
 *
 * <p>顺序固定：
 * <ol>
 *   <li>完整目标 + 有限辅助材料能容纳时，保持整文件；</li>
 *   <li>未开启智能拆分时直接以 CONTEXT_LIMIT 失败，不偷偷拆分；</li>
 *   <li>开启后先从低优先级辅助材料开始裁剪，仍超限才按类型/成员边界拆分；</li>
 *   <li>单元仍超限时按原始行区间有限细分，单行都放不下才 UNSPLITTABLE。</li>
 * </ol>
 *
 * <p>拆分只产生带原始行号的片段材料，不截断单行，不改变目标 SHA/路径/侧；
 * 合并与去重由 {@link SpecialistResultMerger} 在结果层完成，不追加 LLM 综合复查。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class SpecialistSplitPlanner {

    private final PromptAssemblyService assembly;
    private final ModelCapacityService capacity;
    private final SpecialistPromptBuilder prompts;
    private final MaterialStore store;
    private final SpecialistProperties properties;

    public SpecialistSplitPlanner(PromptAssemblyService assembly, ModelCapacityService capacity,
                                  SpecialistPromptBuilder prompts, MaterialStore store,
                                  SpecialistProperties properties) {
        this.assembly = assembly;
        this.capacity = capacity;
        this.prompts = prompts;
        this.store = store;
        this.properties = properties;
    }

    public List<SpecialistUnitPlan> plan(PreparedTarget target, CouplingFacts facts,
                                         String strategyName, String strategyPrompt,
                                         ModelSnapshot model, boolean smartSplit) {
        Attempt full = fit(target, facts, strategyName, strategyPrompt, model);
        if (full.fits()) {
            return List.of(new SpecialistUnitPlan(1, target, ranges(target.targets()), List.of()));
        }
        // 完整目标 + 原辅助材料不 fit；以下只在授权拆分或裁剪后继续。
        if (!smartSplit) {
            throw contextLimit(target.path(), full.estimate(),
                    "未开启智能拆分，完整请求超过上下文窗口");
        }

        List<Material> auxiliary = new ArrayList<>(target.auxiliary());
        int removed = 0;
        while (!auxiliary.isEmpty()) {
            auxiliary.remove(auxiliary.size() - 1);
            removed++;
            PreparedTarget candidate = variant(target, target.targets(), auxiliary);
            Attempt attempt = fit(candidate, facts, strategyName, strategyPrompt, model);
            if (attempt.fits()) {
                return List.of(new SpecialistUnitPlan(1, candidate, ranges(candidate.targets()),
                        List.of("为容纳完整目标，移除了 " + removed + " 个低优先级辅助材料")));
            }
            // 继续移除更低优先级的辅助材料。
        }

        List<Material> headers = headerFragments(target);
        List<Block> blocks = blocks(target);
        if (blocks.isEmpty()) {
            if (!headers.isEmpty() && fits(variant(target, headers, List.of()), facts,
                    strategyName, strategyPrompt, model)) {
                return List.of(new SpecialistUnitPlan(1, variant(target, headers, List.of()),
                        ranges(headers), List.of("目标只有类型声明，按声明单元发送")));
            }
            throw new ApiException(ErrorCode.UNSPLITTABLE,
                    "目标没有可拆分的代码块：" + target.path(),
                    Map.of("path", target.path()));
        }

        List<SpecialistUnitPlan> units = new ArrayList<>();
        List<Material> current = new ArrayList<>(headers);
        int currentBlocks = 0;
        for (Block block : blocks) {
            Material fragment = fragment(block.material(), block.start(), block.end(), block.kind());
            List<Material> candidate = new ArrayList<>(current);
            candidate.add(fragment);
            if (fits(variant(target, candidate, List.of()), facts, strategyName, strategyPrompt, model)) {
                current.add(fragment);
                currentBlocks++;
                continue;
            }
            if (currentBlocks > 0) {
                units.add(unit(units.size() + 1, target, current, List.of()));
                current = new ArrayList<>(headers);
                currentBlocks = 0;
            }
            if (fits(variant(target, append(headers, fragment), List.of()), facts, strategyName,
                    strategyPrompt, model)) {
                current.add(fragment);
                currentBlocks++;
            } else {
                currentBlocks += splitOversized(target, facts, strategyName, strategyPrompt, model,
                        headers, block, current, units);
            }
            if (units.size() > properties.getMaxUnitsPerFile()) {
                throw new ApiException(ErrorCode.UNSPLITTABLE,
                        "文件拆分超过 " + properties.getMaxUnitsPerFile() + " 个单元，停止",
                        Map.of("path", target.path(), "units", units.size()));
            }
        }
        if (currentBlocks > 0 || units.isEmpty()) {
            units.add(unit(units.size() + 1, target, current, List.of()));
        }
        if (units.size() > properties.getMaxUnitsPerFile()) {
            throw new ApiException(ErrorCode.UNSPLITTABLE,
                    "文件拆分超过 " + properties.getMaxUnitsPerFile() + " 个单元，停止",
                    Map.of("path", target.path(), "units", units.size()));
        }
        List<SpecialistUnitPlan> withAuxiliary = new ArrayList<>();
        for (SpecialistUnitPlan unit : units) {
            withAuxiliary.add(withFittingAuxiliary(target, unit, facts, strategyName, strategyPrompt,
                    model));
        }
        return withAuxiliary;
    }

    /** 拆分单元仍尽量携带能容纳的辅助声明；放不下时保持已裁剪单元。 */
    private SpecialistUnitPlan withFittingAuxiliary(PreparedTarget original, SpecialistUnitPlan unit,
                                                     CouplingFacts facts, String strategyName,
                                                     String strategyPrompt, ModelSnapshot model) {
        if (original.auxiliary().isEmpty()) {
            return unit;
        }
        List<Material> chosen = new ArrayList<>();
        for (Material material : original.auxiliary()) {
            List<Material> candidate = new ArrayList<>(chosen);
            candidate.add(material);
            PreparedTarget variant = variant(original, unit.target().targets(), candidate);
            if (fits(variant, facts, strategyName, strategyPrompt, model)) {
                chosen = candidate;
            } else {
                break;
            }
        }
        if (chosen.isEmpty()) {
            return unit;
        }
        List<String> notes = new ArrayList<>(unit.notes());
        notes.add("单元保留 " + chosen.size() + " 个辅助声明");
        return new SpecialistUnitPlan(unit.ordinal(),
                variant(original, unit.target().targets(), chosen), unit.ranges(), notes);
    }

    /**
     * 单个块超限时按行二分；返回加入当前单元的块数。
     */
    private int splitOversized(PreparedTarget target, CouplingFacts facts, String strategyName,
                               String strategyPrompt, ModelSnapshot model, List<Material> headers,
                               Block block, List<Material> current, List<SpecialistUnitPlan> units) {
        List<Block> pending = new ArrayList<>();
        pending.add(block);
        int added = 0;
        while (!pending.isEmpty()) {
            Block part = pending.remove(0);
            Material fragment = fragment(part.material(), part.start(), part.end(), part.kind());
            if (fits(variant(target, append(headers, fragment), List.of()), facts, strategyName,
                    strategyPrompt, model)) {
                current.add(fragment);
                added++;
                continue;
            }
            if (part.end() <= part.start()) {
                throw new ApiException(ErrorCode.UNSPLITTABLE,
                        "单行材料仍超过模型容量，无法继续拆分：" + part.material().path()
                                + ":" + part.start(),
                        Map.of("path", part.material().path(), "line", part.start()));
            }
            int middle = (part.start() + part.end()) / 2;
            pending.add(0, new Block(part.material(), middle + 1, part.end(), part.kind()));
            pending.add(0, new Block(part.material(), part.start(), middle, part.kind()));
        }
        return added;
    }

    private Attempt fit(PreparedTarget target, CouplingFacts facts, String strategyName,
                        String strategyPrompt, ModelSnapshot model) {
        PromptInput input = prompts.build(target, facts, strategyName, strategyPrompt);
        AssembledPrompt assembled = assembly.assemble(input);
        CapacityEstimate estimate = capacity.estimate(assembled, model);
        return new Attempt(estimate.fits(), estimate);
    }

    private boolean fits(PreparedTarget target, CouplingFacts facts, String strategyName,
                         String strategyPrompt, ModelSnapshot model) {
        return fit(target, facts, strategyName, strategyPrompt, model).fits();
    }

    private SpecialistUnitPlan unit(int ordinal, PreparedTarget original, List<Material> materials,
                                    List<String> notes) {
        PreparedTarget variant = variant(original, materials, List.of());
        return new SpecialistUnitPlan(ordinal, variant, ranges(materials), notes);
    }

    private static PreparedTarget variant(PreparedTarget original, List<Material> targets,
                                          List<Material> auxiliary) {
        return new PreparedTarget(original.projectId(), original.specialistType(),
                original.sourceKind(), original.branch(), original.path(), original.oldPath(),
                original.headSha(), original.baseSha(), original.note(), original.added(),
                original.removed(), targets, auxiliary, original.gaps(), null, null);
    }

    private List<Material> headerFragments(PreparedTarget target) {
        List<Material> headers = new ArrayList<>();
        for (Material material : target.targets()) {
            if (material.structure() == null) {
                continue;
            }
            JavaSourceStructure structure = material.structure();
            int firstType = structure.types().stream().mapToInt(JavaSourceStructure.TypeDecl::startLine)
                    .min().orElse(1);
            if (firstType > 1) {
                headers.add(fragment(material, 1, firstType - 1, "file-header"));
            }
            for (JavaSourceStructure.TypeDecl type : structure.types()) {
                if (type.outerQualifiedName() != null) {
                    continue;
                }
                if (type.memberRanges().isEmpty()) {
                    headers.add(fragment(material, type.startLine(), type.endLine(), "type"));
                    continue;
                }
                int firstMember = type.memberRanges().stream()
                        .mapToInt(JavaSourceStructure.MemberRange::startLine)
                        .min().orElse(type.startLine());
                int headerEnd = Math.max(type.startLine(), firstMember - 1);
                headers.add(fragment(material, type.startLine(), headerEnd, "type-header"));
            }
        }
        return headers;
    }

    private List<Block> blocks(PreparedTarget target) {
        List<Block> blocks = new ArrayList<>();
        for (Material material : target.targets()) {
            if (material.structure() == null) {
                addLineChunks(blocks, material, 1, material.endLine());
                continue;
            }
            JavaSourceStructure structure = material.structure();
            for (JavaSourceStructure.TypeDecl type : structure.types()) {
                if (type.outerQualifiedName() != null) {
                    continue;
                }
                List<JavaSourceStructure.MemberRange> members = new ArrayList<>(type.memberRanges());
                members.sort(Comparator.comparingInt(JavaSourceStructure.MemberRange::startLine));
                for (JavaSourceStructure.MemberRange member : members) {
                    blocks.add(new Block(material, member.startLine(), member.endLine(), member.kind()));
                }
                if (!members.isEmpty()) {
                    int tailStart = members.get(members.size() - 1).endLine() + 1;
                    if (tailStart <= type.endLine()) {
                        blocks.add(new Block(material, tailStart, type.endLine(), "type-tail"));
                    }
                }
            }
        }
        blocks.sort(Comparator.comparing((Block block) -> block.material().id())
                .thenComparingInt(Block::start));
        return dedupe(blocks);
    }

    private void addLineChunks(List<Block> blocks, Material material, int start, int end) {
        int step = Math.max(1, properties.getMaxBlockLines());
        for (int from = start; from <= end; from += step) {
            blocks.add(new Block(material, from, Math.min(end, from + step - 1), "lines"));
        }
    }

    private static List<Block> dedupe(List<Block> blocks) {
        Set<String> seen = new LinkedHashSet<>();
        List<Block> result = new ArrayList<>();
        for (Block block : blocks) {
            String key = block.material().id() + "|" + block.start() + "|" + block.end();
            if (seen.add(key)) {
                result.add(block);
            }
        }
        return result;
    }

    private Material fragment(Material source, int start, int end, String kind) {
        String content = slice(source.content(), start, end);
        Material fragment = new Material(AgentIds.next(), source.projectId(), source.sha(),
                source.path(), source.side(), start, end, content, ContentHash.sha256(content),
                "FRAGMENT", "拆分片段（" + kind + "），原始行 " + start + "-" + end,
                Role.TARGET, Status.RESOLVED, null, null);
        return store.save(fragment);
    }

    private static List<Material> append(List<Material> head, Material tail) {
        List<Material> result = new ArrayList<>(head);
        result.add(tail);
        return result;
    }

    private static List<JavaSourceStructure.LineRange> ranges(List<Material> materials) {
        List<JavaSourceStructure.LineRange> ranges = new ArrayList<>();
        for (Material material : materials) {
            ranges.add(new JavaSourceStructure.LineRange(material.startLine(), material.endLine()));
        }
        return ranges;
    }

    static String slice(String content, int startLine, int endLine) {
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int from = Math.max(1, startLine);
        int to = Math.min(lines.length, endLine);
        StringBuilder text = new StringBuilder();
        for (int line = from; line <= to; line++) {
            if (line > from) {
                text.append('\n');
            }
            text.append(lines[line - 1]);
        }
        return text.toString();
    }

    private static ApiException contextLimit(String path, CapacityEstimate estimate, String detail) {
        return new ApiException(ErrorCode.CONTEXT_LIMIT, detail + "：" + path,
                Map.of("path", path,
                        "inputEstimate", estimate.inputEstimate(),
                        "outputReserve", estimate.outputReserve(),
                        "safetyReserve", estimate.safetyReserve(),
                        "required", estimate.required(),
                        "contextWindow", estimate.contextWindow()));
    }

    private record Attempt(boolean fits, CapacityEstimate estimate) {
    }

    private record Block(Material material, int start, int end, String kind) {
    }
}
