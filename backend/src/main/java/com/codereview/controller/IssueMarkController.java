package com.codereview.controller;

import com.codereview.common.Result;
import com.codereview.dto.AccuracyStat;
import com.codereview.dto.IssueMarkReq;
import com.codereview.entity.IssueMark;
import com.codereview.service.IssueMarkService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** issue 标记与准确率统计接口（服务验收指标 5 的复核入口）。 */
@RestController
@RequestMapping("/api")
public class IssueMarkController {

    private final IssueMarkService issueMarkService;

    public IssueMarkController(IssueMarkService issueMarkService) {
        this.issueMarkService = issueMarkService;
    }

    /** 打标 / 改标；markValue: 0 未标记(撤销) / 1 误报 / 2 已采纳。 */
    @PostMapping("/reviews/{recordId}/marks")
    public Result<IssueMark> mark(@PathVariable Long recordId, @RequestBody IssueMarkReq req) {
        return Result.ok(issueMarkService.mark(recordId, req.unitPath(), req.issueIndex(), req.markValue()));
    }

    /** 撤销标记。 */
    @DeleteMapping("/reviews/{recordId}/marks")
    public Result<Void> unmark(@PathVariable Long recordId,
                               @RequestParam String unitPath,
                               @RequestParam Integer issueIndex) {
        issueMarkService.unmark(recordId, unitPath, issueIndex);
        return Result.ok();
    }

    /** 某条记录的标记清单。 */
    @GetMapping("/reviews/{recordId}/marks")
    public Result<List<IssueMark>> list(@PathVariable Long recordId) {
        return Result.ok(issueMarkService.list(recordId));
    }

    /** 项目维度的准确率统计：按分析器类型展开，区分 static（基础静态规则）与 ai（AI 规则）。 */
    @GetMapping("/projects/{projectId}/accuracy")
    public Result<List<AccuracyStat>> accuracy(@PathVariable Long projectId) {
        return Result.ok(issueMarkService.accuracy(projectId));
    }
}
