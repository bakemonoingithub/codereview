package com.codereview.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.Result;
import com.codereview.common.TextDiff;
import com.codereview.dto.PromptContentUpdateReq;
import com.codereview.dto.PromptCreateReq;
import com.codereview.dto.PromptDetailResp;
import com.codereview.dto.PromptUpdateReq;
import com.codereview.entity.Prompt;
import com.codereview.entity.PromptVersion;
import com.codereview.service.PromptService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/prompts")
public class PromptController {

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @PostMapping
    public Result<Prompt> create(@RequestBody PromptCreateReq req) {
        return Result.ok(promptService.create(req));
    }

    @GetMapping
    public Result<Page<Prompt>> list(@RequestParam(defaultValue = "1") long pageNum,
                                     @RequestParam(defaultValue = "10") long pageSize,
                                     @RequestParam(required = false) String keyword) {
        return Result.ok(promptService.list(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    public Result<PromptDetailResp> detail(@PathVariable Long id) {
        return Result.ok(promptService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<Prompt> update(@PathVariable Long id, @RequestBody PromptUpdateReq req) {
        return Result.ok(promptService.update(id, req));
    }

    @PutMapping("/{id}/content")
    public Result<PromptVersion> updateContent(@PathVariable Long id, @RequestBody PromptContentUpdateReq req) {
        return Result.ok(promptService.updateContent(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        promptService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}/versions/diff")
    public Result<List<TextDiff.DiffLine>> diff(@PathVariable Long id,
                                                @RequestParam Long from,
                                                @RequestParam Long to) {
        return Result.ok(promptService.diff(id, from, to));
    }
}
