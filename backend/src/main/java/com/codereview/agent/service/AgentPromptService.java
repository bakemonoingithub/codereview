package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.PromptDtos;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.entity.AgentReviewStrategy;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.mapper.AgentReviewStrategyMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.codereview.common.TextDiff;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 提示词 v2 服务。
 *
 * <p>新建生成版本 1；正文变更可追加新版本或覆盖当前版本，元数据更新单独一条，
 * 所有写入都带 revision 条件。非当前历史版本不修改，实际调用另存快照。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentPromptService {

    private static final String DEFAULT_PURPOSE = "SPECIALIST";

    private final AgentDatabase db;

    public AgentPromptService(AgentDatabase db) {
        this.db = db;
    }

    public PageResponse<PromptDtos.Summary> list(String q, String cursor, Integer limit) {
        return list(q, cursor, limit, null, null);
    }

    public PageResponse<PromptDtos.Summary> list(String q, String cursor, Integer limit, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<AgentPrompt> wrapper = new LambdaQueryWrapper<>();
        if (q != null && !q.isBlank()) {
            wrapper.and(w -> w.like(AgentPrompt::getName, q.trim())
                    .or().like(AgentPrompt::getTags, q.trim())
                    .or().like(AgentPrompt::getDescription, q.trim()));
        }
        PageResponse<AgentPrompt> page = AgentPaging.query(
                db.mapper(AgentPromptMapper.class), wrapper, cursor, limit, pageNum, pageSize,
                AgentPrompt::getCreatedAt, AgentPrompt::getId);
        List<String> currentVersionIds = page.items().stream()
                .map(AgentPrompt::getCurrentVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, Integer> versionNoById = currentVersionIds.isEmpty()
                ? Map.of()
                : db.mapper(AgentPromptVersionMapper.class).selectBatchIds(currentVersionIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                AgentPromptVersion::getId, AgentPromptVersion::getVersionNo));
        return page.withItems(page.items().stream()
                .map(p -> new PromptDtos.Summary(p.getId(), p.getName(), p.getPurpose(),
                        splitTags(p.getTags()), p.getRevision(), p.getCurrentVersionId() == null
                                ? null : versionNoById.get(p.getCurrentVersionId()),
                        p.getUpdatedAt()))
                .toList());
    }

    public PromptDtos.View create(PromptDtos.Create req) {
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(AgentIds.next());
        prompt.setName(req.name().trim());
        prompt.setDescription(blankToNull(req.description()));
        prompt.setTags(joinTags(req.tags()));
        prompt.setPurpose(req.purpose() == null ? DEFAULT_PURPOSE : req.purpose());
        prompt.setRevision(0);
        prompt.setCreatedAt(AgentTime.now());
        prompt.setUpdatedAt(AgentTime.now());
        prompt.setIsDeleted(0);

        return db.inTransaction(() -> {
            AgentPromptVersion version = new AgentPromptVersion();
            version.setId(AgentIds.next());
            version.setPromptId(prompt.getId());
            version.setVersionNo(1);
            version.setContent(req.content());
            version.setCreatedAt(AgentTime.now());
            db.mapper(AgentPromptVersionMapper.class).insert(version);

            prompt.setCurrentVersionId(version.getId());
            db.mapper(AgentPromptMapper.class).insert(prompt);
            return toView(prompt, version);
        });
    }

    public PromptDtos.View update(String id, PromptDtos.Update req) {
        AgentPrompt current = require(id);
        if (current.getRevision() != req.expectedRevision()) {
            throw conflict(current.getRevision());
        }
        // 用 UpdateWrapper 显式 set：描述与标签允许清空，实体更新会忽略 null 而保留旧值。
        LambdaUpdateWrapper<AgentPrompt> where = new LambdaUpdateWrapper<AgentPrompt>()
                .eq(AgentPrompt::getId, id)
                .eq(AgentPrompt::getRevision, req.expectedRevision())
                .set(AgentPrompt::getName, req.name().trim())
                .set(AgentPrompt::getDescription, blankToNull(req.description()))
                .set(AgentPrompt::getTags, joinTags(req.tags()))
                .set(AgentPrompt::getRevision, req.expectedRevision() + 1)
                .set(AgentPrompt::getUpdatedAt, AgentTime.now());
        if (req.purpose() != null) {
            where.set(AgentPrompt::getPurpose, req.purpose());
        }
        int affected = db.mapper(AgentPromptMapper.class).update(null, where);
        if (affected == 0) {
            throw conflict(require(id).getRevision());
        }
        return toView(require(id), currentVersion(require(id)));
    }

    /** 保存正文：默认追加新版本；显式选择时可覆盖当前版本。 */
    public PromptDtos.View addVersion(String id, PromptDtos.VersionCreate req) {
        AgentPrompt prompt = require(id);
        if (prompt.getRevision() != req.expectedRevision()) {
            throw conflict(prompt.getRevision());
        }
        return db.inTransaction(() -> {
            if (Boolean.FALSE.equals(req.createNewVersion())) {
                return overwriteCurrentVersion(prompt, req);
            }

            List<AgentPromptVersion> latest = db.mapper(AgentPromptVersionMapper.class).selectList(
                    new LambdaQueryWrapper<AgentPromptVersion>()
                            .eq(AgentPromptVersion::getPromptId, id)
                            .orderByDesc(AgentPromptVersion::getVersionNo)
                            .last("limit 1"));
            int nextNo = latest.isEmpty() ? 1 : latest.get(0).getVersionNo() + 1;

            AgentPromptVersion version = new AgentPromptVersion();
            version.setId(AgentIds.next());
            version.setPromptId(id);
            version.setVersionNo(nextNo);
            version.setContent(req.content());
            version.setCreatedAt(AgentTime.now());
            db.mapper(AgentPromptVersionMapper.class).insert(version);

            AgentPrompt patch = new AgentPrompt();
            patch.setCurrentVersionId(version.getId());
            patch.setRevision(req.expectedRevision() + 1);
            patch.setUpdatedAt(AgentTime.now());
            int affected = db.mapper(AgentPromptMapper.class).update(patch,
                    new LambdaUpdateWrapper<AgentPrompt>()
                            .eq(AgentPrompt::getId, id)
                            .eq(AgentPrompt::getRevision, req.expectedRevision()));
            if (affected == 0) {
                throw conflict(require(id).getRevision());
            }
            return toView(require(id), version);
        });
    }

    /** 覆盖当前版本正文，不改变版本号；所有任务仍使用创建时保存的正文快照。 */
    private PromptDtos.View overwriteCurrentVersion(AgentPrompt prompt, PromptDtos.VersionCreate req) {
        AgentPromptVersion version = currentVersion(prompt);
        if (version == null) {
            version = new AgentPromptVersion();
            version.setId(AgentIds.next());
            version.setPromptId(prompt.getId());
            version.setVersionNo(1);
            version.setContent(req.content());
            version.setCreatedAt(AgentTime.now());
            db.mapper(AgentPromptVersionMapper.class).insert(version);

            AgentPrompt initial = new AgentPrompt();
            initial.setCurrentVersionId(version.getId());
            initial.setRevision(req.expectedRevision() + 1);
            initial.setUpdatedAt(AgentTime.now());
            int affected = db.mapper(AgentPromptMapper.class).update(initial,
                    new LambdaUpdateWrapper<AgentPrompt>()
                            .eq(AgentPrompt::getId, prompt.getId())
                            .eq(AgentPrompt::getRevision, req.expectedRevision()));
            if (affected == 0) {
                throw conflict(require(prompt.getId()).getRevision());
            }
            return toView(require(prompt.getId()), version);
        }

        AgentPrompt patch = new AgentPrompt();
        patch.setRevision(req.expectedRevision() + 1);
        patch.setUpdatedAt(AgentTime.now());
        int promptAffected = db.mapper(AgentPromptMapper.class).update(patch,
                new LambdaUpdateWrapper<AgentPrompt>()
                        .eq(AgentPrompt::getId, prompt.getId())
                        .eq(AgentPrompt::getRevision, req.expectedRevision()));
        if (promptAffected == 0) {
            throw conflict(require(prompt.getId()).getRevision());
        }

        int versionAffected = db.mapper(AgentPromptVersionMapper.class).update(null,
                new LambdaUpdateWrapper<AgentPromptVersion>()
                        .eq(AgentPromptVersion::getId, version.getId())
                        .eq(AgentPromptVersion::getPromptId, prompt.getId())
                        .set(AgentPromptVersion::getContent, req.content()));
        if (versionAffected == 0 && !Objects.equals(version.getContent(), req.content())) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "覆盖当前提示词版本失败", Map.of("promptId", prompt.getId()));
        }
        version.setContent(req.content());
        return toView(require(prompt.getId()), version);
    }

    public List<PromptDtos.VersionView> versions(String id) {
        require(id);
        return db.mapper(AgentPromptVersionMapper.class).selectList(
                        new LambdaQueryWrapper<AgentPromptVersion>()
                                .eq(AgentPromptVersion::getPromptId, id)
                                .orderByDesc(AgentPromptVersion::getVersionNo))
                .stream().map(AgentPromptService::toVersionView).toList();
    }

    /** 版本对比：只接受同一提示词下的两个版本，避免跨提示词"对比"出误导结果。 */
    public List<TextDiff.DiffLine> diff(String id, String fromVersionId, String toVersionId) {
        require(id);
        AgentPromptVersion from = requireVersion(id, fromVersionId);
        AgentPromptVersion to = requireVersion(id, toVersionId);
        return TextDiff.diff(from.getContent(), to.getContent());
    }

    public PromptDtos.View detail(String id) {
        AgentPrompt prompt = require(id);
        return toView(prompt, currentVersion(prompt));
    }

    public void delete(String id) {
        AgentPrompt prompt = require(id);
        Long used = db.mapper(AgentReviewStrategyMapper.class).selectCount(
                new LambdaQueryWrapper<AgentReviewStrategy>().eq(AgentReviewStrategy::getPromptId, prompt.getId()));
        if (used != null && used > 0) {
            throw new ApiException(ErrorCode.CONFIG_IN_USE,
                    "该提示词仍被 " + used + " 个策略引用，请先调整关联", Map.of("strategies", used));
        }
        db.mapper(AgentPromptMapper.class).deleteById(prompt.getId());
    }

    private AgentPrompt require(String id) {
        AgentPrompt prompt = db.mapper(AgentPromptMapper.class).selectById(AgentIds.requireValid(id, "提示词 ID"));
        if (prompt == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "提示词不存在：" + id);
        }
        return prompt;
    }

    private AgentPromptVersion requireVersion(String promptId, String versionId) {
        AgentPromptVersion version = db.mapper(AgentPromptVersionMapper.class)
                .selectById(AgentIds.requireValid(versionId, "版本 ID"));
        if (version == null || !Objects.equals(version.getPromptId(), promptId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "版本不属于该提示词：" + versionId);
        }
        return version;
    }

    private AgentPromptVersion currentVersion(AgentPrompt prompt) {
        if (prompt.getCurrentVersionId() == null) {
            return null;
        }
        return db.mapper(AgentPromptVersionMapper.class).selectById(prompt.getCurrentVersionId());
    }

    private PromptDtos.View toView(AgentPrompt prompt, AgentPromptVersion version) {
        return new PromptDtos.View(prompt.getId(), prompt.getName(), prompt.getDescription(),
                splitTags(prompt.getTags()), prompt.getPurpose(),
                version == null ? null : toVersionView(version), prompt.getRevision(),
                prompt.getCreatedAt(), prompt.getUpdatedAt());
    }

    private static PromptDtos.VersionView toVersionView(AgentPromptVersion version) {
        return new PromptDtos.VersionView(version.getId(), version.getVersionNo(), version.getContent(),
                version.getCreatedAt());
    }

    private static ApiException conflict(int currentRevision) {
        return new ApiException(ErrorCode.REVISION_CONFLICT, "提示词已被其他操作修改，请刷新后重试",
                Map.of("currentRevision", currentRevision));
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String joined = String.join(",", tags.stream().filter(t -> t != null && !t.isBlank())
                .map(String::trim).distinct().toList());
        return joined.isBlank() ? null : joined;
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(t -> !t.isBlank()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
