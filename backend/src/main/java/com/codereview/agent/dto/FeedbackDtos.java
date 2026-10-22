package com.codereview.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 人工反馈：评价与评论独立保存，revision 用于并发保护。 */
public final class FeedbackDtos {
    private FeedbackDtos() { }

    public record View(String subjectKind, String subjectId, String assessment,
                       String comment, int revision, LocalDateTime updatedAt) { }

    public record Update(
            @Pattern(regexp = "ACCURATE|INACCURATE|UNKNOWN|", message = "评价只能是 ACCURATE、INACCURATE、UNKNOWN 或空")
            String assessment,
            @Size(max = 16000, message = "评论不能超过 16000 个字符") String comment,
            @Min(value = 0, message = "expectedRevision 不能小于 0") Integer expectedRevision) { }
}
