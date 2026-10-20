package com.codereview.agent.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCursorTest {

    @Test
    void roundTripsTimeAndId() {
        String id = AgentIds.next();
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 20, 14, 7, 40, 123_000_000);
        String cursor = AgentCursor.encode(createdAt, id);
        AgentCursor.CursorValue value = AgentCursor.decode(cursor);
        assertEquals(createdAt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), value.createdAtMillis());
        assertEquals(id, value.id());
        assertEquals(createdAt, AgentCursor.toLocalDateTime(value.createdAtMillis()));
    }

    @Test
    void blankCursorMeansFirstPage() {
        assertNull(AgentCursor.decode(null));
        assertNull(AgentCursor.decode(" "));
    }

    @Test
    void rejectsGarbageWithoutLeakingInternals() {
        assertThrows(IllegalArgumentException.class, () -> AgentCursor.decode("not-base64!!"));
        assertThrows(IllegalArgumentException.class, () -> AgentCursor.decode("MTIzNDU2"));
        String badId = AgentCursor.encode(LocalDateTime.now(), "短ID");
        assertThrows(IllegalArgumentException.class, () -> AgentCursor.decode(badId));
    }
}
