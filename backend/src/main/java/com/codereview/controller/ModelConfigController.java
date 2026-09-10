package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.dto.ModelConfigReq;
import com.codereview.entity.ModelConfig;
import com.codereview.service.ModelConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    public ModelConfigController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @GetMapping
    public Result<Page<ModelConfig>> list(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(modelConfigService.list(pageNum, pageSize));
    }

    @PostMapping
    public Result<ModelConfig> create(@RequestBody ModelConfigReq req) {
        return Result.ok(modelConfigService.create(req));
    }

    @PutMapping("/{id}")
    public Result<ModelConfig> update(@PathVariable Long id, @RequestBody ModelConfigReq req) {
        return Result.ok(modelConfigService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/verify")
    public Result<ModelConfig> verify(@PathVariable Long id) {
        return Result.ok(modelConfigService.verify(id));
    }
}
