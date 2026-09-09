package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.dto.StrategyReq;
import com.codereview.entity.ReviewStrategy;
import com.codereview.service.ReviewStrategyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/strategies")
public class ReviewStrategyController {

    private final ReviewStrategyService strategyService;

    public ReviewStrategyController(ReviewStrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @GetMapping
    public Result<Page<ReviewStrategy>> list(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(strategyService.list(pageNum, pageSize));
    }

    @PostMapping
    public Result<ReviewStrategy> create(@RequestBody StrategyReq req) {
        return Result.ok(strategyService.create(req));
    }
}
