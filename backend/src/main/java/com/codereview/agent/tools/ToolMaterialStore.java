package com.codereview.agent.tools;

import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentMaterial;
import com.codereview.agent.mapper.AgentMaterialMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 工具材料的保存：同一固定版本、路径、侧、行范围与正文哈希只存一份。
 *
 * <p>重复查询（例如模型再次读同一段）会复用原材料 ID，引用因此保持稳定；
 * 同路径不同 SHA、不同行范围或内容变化都不会命中旧记录。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ToolMaterialStore {

    private final AgentDatabase db;

    public ToolMaterialStore(AgentDatabase db) {
        this.db = db;
    }

    public ToolEnvelope.ToolMaterial save(String projectId, ToolEnvelope.ToolMaterial material) {
        AgentMaterialMapper mapper = db.mapper(AgentMaterialMapper.class);
        AgentMaterial existing = find(mapper, projectId, material);
        if (existing != null) {
            return new ToolEnvelope.ToolMaterial(existing.getId(), material.path(), material.side(),
                    material.sha(), material.startLine(), material.endLine(), material.contentHash(),
                    material.completeness(), material.content());
        }
        String id = AgentIds.next();
        AgentMaterial row = new AgentMaterial();
        row.setId(id);
        row.setProjectId(projectId);
        row.setSha(material.sha());
        row.setPath(material.path());
        row.setSide(material.side() == null ? "FILE" : material.side());
        row.setStartLine(material.startLine());
        row.setEndLine(material.endLine());
        row.setContent(material.content());
        row.setContentHash(material.contentHash());
        row.setCompleteness(material.completeness() == null ? ToolEnvelope.COMPLETE : material.completeness());
        row.setCreatedAt(AgentTime.now());
        mapper.insert(row);
        return new ToolEnvelope.ToolMaterial(id, material.path(), material.side(), material.sha(),
                material.startLine(), material.endLine(), material.contentHash(),
                material.completeness(), material.content());
    }

    private AgentMaterial find(AgentMaterialMapper mapper, String projectId,
                               ToolEnvelope.ToolMaterial material) {
        LambdaQueryWrapper<AgentMaterial> query = new LambdaQueryWrapper<AgentMaterial>()
                .eq(AgentMaterial::getProjectId, projectId)
                .eq(AgentMaterial::getSha, material.sha())
                .eq(AgentMaterial::getPath, material.path())
                .eq(AgentMaterial::getSide, material.side() == null ? "FILE" : material.side())
                .eq(AgentMaterial::getContentHash, material.contentHash());
        if (material.startLine() != null) {
            query.eq(AgentMaterial::getStartLine, material.startLine());
        }
        if (material.endLine() != null) {
            query.eq(AgentMaterial::getEndLine, material.endLine());
        }
        List<AgentMaterial> rows = mapper.selectList(query.last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
