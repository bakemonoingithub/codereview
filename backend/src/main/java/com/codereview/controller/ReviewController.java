package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.dto.ReviewRecordResp;
import com.codereview.dto.ReviewRecordRow;
import com.codereview.dto.ReviewTriggerReq;
import com.codereview.entity.ReviewRecord;
import com.codereview.service.ReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/projects/{projectId}/reviews/trigger")
    public Result<ReviewRecord> trigger(@PathVariable Long projectId, @RequestBody ReviewTriggerReq req) {
        return Result.ok(reviewService.trigger(projectId, req));
    }

    @PostMapping("/reviews/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        reviewService.retry(id);
        return Result.ok();
    }

    /**
     * 审查记录列表（分页）。
     *
     * @param statusMin 可选的状态下限：报告生成页只要"已完成"的记录（传 2），
     *                  否则未完成记录（还没有 resultJson）会占满整页、甚至出现空页。
     */
    @GetMapping("/projects/{projectId}/reviews")
    public Result<Page<ReviewRecordRow>> list(@PathVariable Long projectId,
                                              @RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) Integer statusMin) {
        return Result.ok(reviewService.list(projectId, pageNum, pageSize, statusMin));
    }

    @GetMapping("/reviews/{id}")
    public Result<ReviewRecordResp> detail(@PathVariable Long id) {
        return Result.ok(reviewService.detail(id));
    }
}
