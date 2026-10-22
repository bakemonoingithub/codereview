package com.codereview.agent.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdsTest {

    @Test
    void generatesTwentySixCrockfordCharacters() {
        for (int i = 0; i < 200; i++) {
            String id = AgentIds.next();
            assertEquals(26, id.length());
            assertTrue(AgentIds.isValid(id), id);
            assertFalse(id.contains("I"));
            assertFalse(id.contains("L"));
            assertFalse(id.contains("O"));
            assertFalse(id.contains("U"));
        }
    }

    @Test
    void doesNotRepeatInBulk() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            assertTrue(seen.add(AgentIds.next()));
        }
    }

    @Test
    void timePrefixSortsWithGenerationOrder() throws InterruptedException {
        String first = AgentIds.next();
        Thread.sleep(5);
        String second = AgentIds.next();
        assertTrue(first.compareTo(second) < 0, first + " 应排在 " + second + " 之前");
    }

    @Test
    void rejectsClientMadeIds() {
        assertFalse(AgentIds.isValid(null));
        assertFalse(AgentIds.isValid("1"));
        assertFalse(AgentIds.isValid("1234567890123456789012345"));
        assertFalse(AgentIds.isValid("1234567890123456789012345U"));
        assertFalse(AgentIds.isValid("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
    }
}
