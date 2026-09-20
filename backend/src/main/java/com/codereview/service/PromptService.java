package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.PageLimits;
import com.codereview.common.ResultCode;
import com.codereview.common.TextDiff;
import com.codereview.dto.PromptContentUpdateReq;
import com.codereview.dto.PromptCreateReq;
import com.codereview.dto.PromptDetailResp;
import com.codereview.dto.PromptUpdateReq;
import com.codereview.entity.Prompt;
import com.codereview.entity.PromptVersion;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提示词管理（M4 完整版）：正文存版本表；支持版本生成、逐行 diff、标签与检索。
 */
@Service
public class PromptService {

    private final PromptMapper promptMapper;
    private final PromptVersionMapper versionMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromptService(PromptMapper promptMapper, PromptVersionMapper versionMapper) {
        this.promptMapper = promptMapper;
        this.versionMapper = versionMapper;
    }

    public Prompt create(PromptCreateReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        ensureNameAvailable(req.name(), null);
        Prompt p = new Prompt();
        p.setName(req.name());
        p.setDescription(req.description());
        p.setTags(serializeTags(req.tags()));
        promptMapper.insert(p);
        PromptVersion v = newPromptVersion(p.getId(), req.content(), 1);
        versionMapper.insert(v);
        p.setCurrentVersionId(v.getId());
        promptMapper.updateById(p);
        return p;
    }

    /**
     * 名称在**未删除**的提示词中必须唯一。
     *
     * <p>原先由数据库唯一索引 {@code uk_name} 保证，但唯一索引 + 逻辑删除会让**已删除的记录
     * 继续占用名称** —— 删掉后用同名重建必然失败。V5 移除该索引后，判重下移到这里
     * （MySQL 不支持"仅对未删除行唯一"的部分唯一索引）。
     *
     * <p>判重依赖 MyBatis-Plus 逻辑删除自动补 {@code is_deleted = 0}：已删除记录不参与，
     * 这正是"删除后可重建"成立的前提。
     *
     * <p>{@code excludeId} 用于编辑场景排除自身 —— 否则"只改描述不改名"会被自己判为重复。
     */
    private void ensureNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<Prompt> w = new LambdaQueryWrapper<Prompt>().eq(Prompt::getName, name);
        if (excludeId != null) {
            w.ne(Prompt::getId, excludeId);
        }
        Long count = promptMapper.selectCount(w);
        // 判空是为了兼容单测里未 stub 的 mapper（Mockito 默认返回 null）
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "提示词名称已存在：" + name);
        }
    }

    public Prompt update(Long id, PromptUpdateReq req) {
        Prompt p = getOrThrow(id);
        p.setName(req.name());
        ensureNameAvailable(p.getName(), p.getId());
        p.setDescription(req.description());
        p.setTags(serializeTags(req.tags()));
        promptMapper.updateById(p);
        return p;
    }

    public PromptVersion updateContent(Long id, PromptContentUpdateReq req) {
        Prompt p = getOrThrow(id);
        if (req.content() == null || req.content().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "正文不能为空");
        }
        if (req.createNewVersion()) {
            int next = nextVersionNo(p.getId());
            PromptVersion v = newPromptVersion(p.getId(), req.content(), next);
            versionMapper.insert(v);
            p.setCurrentVersionId(v.getId());
            promptMapper.updateById(p);
            return v;
        }
        PromptVersion cur = p.getCurrentVersionId() == null ? null : versionMapper.selectById(p.getCurrentVersionId());
        if (cur == null) {
            PromptVersion v = newPromptVersion(p.getId(), req.content(), 1);
            versionMapper.insert(v);
            p.setCurrentVersionId(v.getId());
            promptMapper.updateById(p);
            return v;
        }
        cur.setContent(req.content());
        versionMapper.updateById(cur);
        return cur;
    }

    public void delete(Long id) {
        getOrThrow(id);
        promptMapper.deleteById(id);
        versionMapper.delete(new LambdaQueryWrapper<PromptVersion>().eq(PromptVersion::getPromptId, id));
    }

    public Page<Prompt> list(long pageNum, long pageSize, String keyword) {
        LambdaQueryWrapper<Prompt> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(Prompt::getName, keyword)
                    .or().like(Prompt::getDescription, keyword)
                    .or().like(Prompt::getTags, keyword)
                    .or().apply("id IN (SELECT prompt_id FROM prompt_version WHERE content LIKE {0})",
                            "%" + keyword + "%"));
        }
        w.orderByDesc(Prompt::getUpdatedAt);
        return promptMapper.selectPage(PageLimits.page(pageNum, pageSize), w);
    }

    public PromptDetailResp detail(Long id) {
        Prompt p = getOrThrow(id);
        List<PromptVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<PromptVersion>()
                .eq(PromptVersion::getPromptId, id).orderByAsc(PromptVersion::getVersionNo));
        String currentContent = null;
        Integer currentVersionNo = null;
        if (p.getCurrentVersionId() != null) {
            PromptVersion cur = versionMapper.selectById(p.getCurrentVersionId());
            if (cur != null) {
                currentContent = cur.getContent();
                currentVersionNo = cur.getVersionNo();
            }
        }
        List<PromptDetailResp.VersionResp> vresp = versions.stream()
                .map(v -> new PromptDetailResp.VersionResp(v.getId(), v.getVersionNo(), v.getCreatedAt()))
                .toList();
        return new PromptDetailResp(p.getId(), p.getName(), p.getDescription(), parseTags(p.getTags()),
                p.getCurrentVersionId(), currentVersionNo, currentContent, vresp);
    }

    public List<TextDiff.DiffLine> diff(Long id, Long fromVersionId, Long toVersionId) {
        PromptVersion from = versionMapper.selectById(fromVersionId);
        PromptVersion to = versionMapper.selectById(toVersionId);
        if (from == null || to == null || !id.equals(from.getPromptId()) || !id.equals(to.getPromptId())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "版本不存在");
        }
        return TextDiff.diff(from.getContent(), to.getContent());
    }

    private Prompt getOrThrow(Long id) {
        Prompt p = promptMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.PROMPT_NOT_FOUND);
        }
        return p;
    }

    private int nextVersionNo(Long promptId) {
        PromptVersion last = versionMapper.selectOne(new LambdaQueryWrapper<PromptVersion>()
                .eq(PromptVersion::getPromptId, promptId)
                .orderByDesc(PromptVersion::getVersionNo)
                .last("LIMIT 1"));
        return last == null ? 1 : last.getVersionNo() + 1;
    }

    private PromptVersion newPromptVersion(Long promptId, String content, int versionNo) {
        PromptVersion v = new PromptVersion();
        v.setPromptId(promptId);
        v.setVersionNo(versionNo);
        v.setContent(content);
        return v;
    }

    private String serializeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            throw new IllegalStateException("序列化标签失败", e);
        }
    }

    private List<String> parseTags(String tagsJson) {
        try {
            return tagsJson == null || tagsJson.isBlank() ? List.of()
                    : objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }
}
