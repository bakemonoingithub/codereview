package com.codereview.agent.specialist.material;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentMaterial;
import com.codereview.agent.mapper.AgentMaterialMapper;
import com.codereview.agent.support.AgentTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 新库材料表访问。
 *
 * <p>只保存实际读取到的固定版本内容；{@code structure}、角色和定位状态属于执行期对象，
 * 不写入 {@code material} 表。查询条件包含 SHA 和正文哈希，避免同路径不同版本串用。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentMaterialStore implements MaterialStore {

    private final AgentDatabase db;

    public AgentMaterialStore(AgentDatabase db) {
        this.db = db;
    }

    @Override
    public SpecialistMaterials.Material save(SpecialistMaterials.Material material) {
        AgentMaterialMapper mapper = db.mapper(AgentMaterialMapper.class);
        AgentMaterial existing = find(mapper, material);
        if (existing != null) {
            return material.withId(existing.getId());
        }
        AgentMaterial row = new AgentMaterial();
        row.setId(material.id());
        row.setProjectId(material.projectId());
        row.setSha(material.sha());
        row.setPath(material.path());
        row.setSide(material.side().name());
        row.setStartLine(material.startLine());
        row.setEndLine(material.endLine());
        row.setContent(material.content());
        row.setContentHash(material.contentHash());
        row.setCompleteness(material.completeness());
        row.setDiagnostic(material.diagnostic());
        row.setCreatedAt(AgentTime.now());
        mapper.insert(row);
        return material;
    }

    private AgentMaterial find(AgentMaterialMapper mapper, SpecialistMaterials.Material material) {
        List<AgentMaterial> rows = mapper.selectList(new LambdaQueryWrapper<AgentMaterial>()
                .eq(AgentMaterial::getProjectId, material.projectId())
                .eq(AgentMaterial::getSha, material.sha())
                .eq(AgentMaterial::getPath, material.path())
                .eq(AgentMaterial::getSide, material.side().name())
                .eq(AgentMaterial::getStartLine, material.startLine())
                .eq(AgentMaterial::getEndLine, material.endLine())
                .eq(AgentMaterial::getContentHash, material.contentHash())
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
