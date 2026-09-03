package com.codereview.service;

import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.llm.LlmClient;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审查编排（M1 简化版）：单文件 llm-review，硬编码提示词，逐文件拉取→LLM→聚合结果落库。
 * M2 再接入切分/并行，M3 接入 coupling/design-pattern。
 */
@Slf4j
@Component
public class ReviewExecutor {

    private static final String SYSTEM_PROMPT =
            "你是资深代码审查助手，只输出合法 JSON，不要输出任何其他文字。";
    private static final String USER_TEMPLATE =
            "请审查下面的 Java 代码文件，找出问题（命名规范、代码缺陷、业务规则、设计问题），"
                    + "并以 JSON 返回，格式：{\"issues\":[{\"severity\":\"MAJOR|MINOR|INFO\","
                    + "\"category\":\"...\",\"line\":行号,\"title\":\"...\",\"description\":\"...\","
                    + "\"suggestion\":\"...\"}],\"summary\":\"一句话概述\"}。\n\n文件路径：%s\n代码：\n%s";

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final GitHostClient gitHostClient;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewExecutor(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                          GitHostClient gitHostClient, LlmClient llmClient) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.gitHostClient = gitHostClient;
        this.llmClient = llmClient;
    }

    @Async
    public void execute(Long recordId) {
        ReviewRecord record = reviewRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        record.setStatus(1); // 执行中
        record.setStartedAt(LocalDateTime.now());
        reviewRecordMapper.updateById(record);
        try {
            Project project = projectMapper.selectById(record.getProjectId());
            GitRepoRef ref = GitRepoRef.parse(project.getGiteaUrl());
            List<String> scope = objectMapper.readValue(record.getScopeJson(), new TypeReference<List<String>>() {
            });
            ArrayNode files = objectMapper.createArrayNode();
            int done = 0;
            for (String path : scope) {
                String code = gitHostClient.rawFile(project.getCredential(), ref.owner(), ref.repo(), record.getBranch(), path);
                String content = llmClient.chatJson(SYSTEM_PROMPT, String.format(USER_TEMPLATE, path, code));
                JsonNode result = objectMapper.readTree(content);
                ObjectNode fileNode = objectMapper.createObjectNode();
                fileNode.put("path", path);
                fileNode.set("result", result);
                files.add(fileNode);
                done++;
                record.setProgress((int) (done * 100L / Math.max(1, scope.size())));
                reviewRecordMapper.updateById(record);
            }
            record.setResultJson(objectMapper.writeValueAsString(files));
            record.setStatus(2); // 成功
            record.setProgress(100);
            record.setFinishedAt(LocalDateTime.now());
            reviewRecordMapper.updateById(record);
        } catch (Exception e) {
            log.error("审查执行失败 recordId={}", recordId, e);
            record.setStatus(3); // 失败
            record.setFinishedAt(LocalDateTime.now());
            reviewRecordMapper.updateById(record);
        }
    }
}
