package com.codereview.agent.api;

import com.codereview.agent.config.UiProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit public projection; never serialize application configuration beans. */
@RestController
public class RuntimeConfigController {
    private final UiProperties ui;
    public RuntimeConfigController(UiProperties ui) { this.ui = ui; }
    @GetMapping("/api/v2/runtime-config")
    public ResponseEntity<PublicConfig> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PublicConfig(1,
                ui.getTitle(), ui.getApiBase(), ui.getRequestTimeoutMillis(), ui.getAgentPollMillis(),
                ui.getReportPollMillis(), ui.getSpecialistPollMillis(),
                ui.getModelValidationTimeoutMillis(), ui.getAgentValidationTimeoutMillis()));
    }
    public record PublicConfig(int version, String title, String apiBase, int requestTimeoutMillis,
                               int agentPollMillis, int reportPollMillis, int specialistPollMillis,
                               int modelValidationTimeoutMillis, int agentValidationTimeoutMillis) {}
}
