package com.codereview.agent.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSchemaGuardTest {

    @Test
    void requiresConfiguredDatabaseName() {
        assertDoesNotThrow(() -> AgentSchemaGuard.requireDatabaseName("ai_code_review_agent", "ai_code_review_agent"));
        assertDoesNotThrow(() -> AgentSchemaGuard.requireDatabaseName("ai_code_review_agent", "AI_CODE_REVIEW_AGENT"));
        assertThrows(AgentSchemaMismatchException.class,
                () -> AgentSchemaGuard.requireDatabaseName("ai_code_review_agent", "code_review"));
        assertThrows(AgentSchemaMismatchException.class,
                () -> AgentSchemaGuard.requireDatabaseName("ai_code_review_agent", null));
    }

    @Test
    void refusesDatabaseCarryingLegacyHistory() {
        assertThrows(AgentSchemaMismatchException.class,
                () -> AgentSchemaGuard.requireNotLegacy(true, "code_review"));
        assertDoesNotThrow(() -> AgentSchemaGuard.requireNotLegacy(false, "ai_code_review_agent"));
    }

    @Test
    void refusesHalfInitializedSchema() {
        assertDoesNotThrow(() -> AgentSchemaGuard.requireConsistentHistory(false, false, "db"));
        assertDoesNotThrow(() -> AgentSchemaGuard.requireConsistentHistory(true, true, "db"));
        assertThrows(AgentSchemaMismatchException.class,
                () -> AgentSchemaGuard.requireConsistentHistory(true, false, "db"));
        assertThrows(AgentSchemaMismatchException.class,
                () -> AgentSchemaGuard.requireConsistentHistory(false, true, "db"));
    }

    @Test
    void refusesVersionDriftOrMissingMarker() {
        assertDoesNotThrow(() -> AgentSchemaGuard.requireSchemaVersion("1", "1"));
        assertThrows(AgentSchemaMismatchException.class,
                () -> AgentSchemaGuard.requireSchemaVersion("1", "2"));
        AgentSchemaMismatchException missing = assertThrows(AgentSchemaMismatchException.class,
                () -> AgentSchemaGuard.requireSchemaVersion("1", null));
        assertTrue(missing.getMessage().contains("agent_schema_identity"));
    }
}
