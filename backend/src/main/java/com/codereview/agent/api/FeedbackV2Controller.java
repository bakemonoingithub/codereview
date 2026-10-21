package com.codereview.agent.api;

import com.codereview.agent.dto.FeedbackDtos;
import com.codereview.agent.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/feedback")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class FeedbackV2Controller {
    private final FeedbackService service;

    public FeedbackV2Controller(FeedbackService service) {
        this.service = service;
    }

    @GetMapping("/{subjectKind}/{subjectId}")
    public FeedbackDtos.View get(@PathVariable String subjectKind, @PathVariable String subjectId) {
        return service.get(subjectKind, subjectId);
    }

    @PutMapping("/{subjectKind}/{subjectId}")
    public FeedbackDtos.View save(@PathVariable String subjectKind, @PathVariable String subjectId,
                                  @Valid @RequestBody FeedbackDtos.Update request) {
        return service.save(subjectKind, subjectId, request);
    }
}
