package com.codereview.agent.api;

import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.service.PrAutoReviewService;
import com.codereview.git.GitUserRef;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目 PR 自动审查配置接口。
 */
@RestController
@RequestMapping("/api/v2/projects/{projectId}/auto-review-config")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class PrAutoReviewController {

    private final PrAutoReviewService service;

    public PrAutoReviewController(PrAutoReviewService service) {
        this.service = service;
    }

    @GetMapping
    public PrAutoReviewDtos.ConfigView getConfig(@PathVariable String projectId) {
        return service.getConfig(projectId);
    }

    @PutMapping
    public PrAutoReviewDtos.ConfigView updateConfig(@PathVariable String projectId,
                                                    @Valid @RequestBody PrAutoReviewDtos.ConfigUpdate req) {
        return service.updateConfig(projectId, req);
    }

    @GetMapping("/reviewers/lookup")
    public GitUserRef lookupReviewer(@PathVariable String projectId,
                                     @RequestParam String username) {
        return service.lookupReviewer(projectId, username);
    }
}
