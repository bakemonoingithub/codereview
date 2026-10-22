package com.codereview.agent.specialist.material;

/**
 * 材料来源记录。
 *
 * <p>同一固定版本、路径、侧、行范围和正文哈希只保存一份；重复准备时复用原 ID，
 * 使任务、调用和结果引用保持稳定。内容变化（例如同路径不同 SHA）不会命中旧记录。
 */
public interface MaterialStore {

    SpecialistMaterials.Material save(SpecialistMaterials.Material material);
}
