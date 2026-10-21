package com.codereview.agent.specialist.capacity;

import com.codereview.agent.specialist.material.JavaSourceStructure;
import com.codereview.agent.specialist.material.SpecialistMaterials;

import java.util.List;

/**
 * 一个待执行的审查单元。
 *
 * <p>{@code target} 是只含本单元材料的准备结果；片段材料保留原文件 SHA、路径、侧和
 * 原始行号。{@code ranges} 用于核对覆盖，{@code notes} 记录本单元实际做过的裁剪。
 */
public record SpecialistUnitPlan(int ordinal, SpecialistMaterials.PreparedTarget target,
                                 List<JavaSourceStructure.LineRange> ranges,
                                 List<String> notes) {

    public SpecialistUnitPlan {
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean wholeFile() {
        return target.targets().size() == 1
                && "COMPLETE".equals(target.targets().get(0).completeness());
    }

    public List<String> materialIds() {
        return target.allMaterials().stream().map(SpecialistMaterials.Material::id).toList();
    }
}
