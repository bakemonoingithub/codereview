package com.codereview.agent.api;

import com.codereview.agent.db.AgentDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 新库就绪检查：只报告库身份版本，不探测业务表规模。 */
@RestController
@RequestMapping("/api/v2/health")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentHealthController {

    private final AgentDatabase database;

    public AgentHealthController(AgentDatabase database) {
        this.database = database;
    }

    @GetMapping
    public Map<String, Object> health() {
        return Map.of("database", "agent", "schemaVersion", database.schemaVersion());
    }
}
