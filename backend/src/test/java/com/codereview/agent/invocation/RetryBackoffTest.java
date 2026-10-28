package com.codereview.agent.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryBackoffTest {
    @Test void exponentialDelayJitterAndCapAreIndependent() {
        var timing = new RetryBackoff(30000, 2, 0.2, 100);
        assertEquals(3000, timing.delayMillis(3000, 1, 0.5));
        assertEquals(6000, timing.delayMillis(3000, 2, 0.5));
        assertEquals(12000, timing.delayMillis(3000, 3, 0.5));
        assertEquals(2400, timing.delayMillis(3000, 1, 0));
        assertEquals(3600, timing.delayMillis(3000, 1, 1));
        assertEquals(30000, timing.delayMillis(3000, 10, 1));
    }

    @Test void historicalSnapshotKeepsFixedWaitAndNewSnapshotFreezesAllTiming() throws Exception {
        var mapper = new ObjectMapper();
        var old = mapper.readValue("""
                {"transportRetries":1,"retryBaseMillis":500,"maxReadTimeoutSeconds":180,
                 "safetyReserveMinTokens":1024,"safetyReserveRatio":0.1}
                """, InvocationPolicy.class);
        assertEquals(RetryBackoff.legacy(), old.retryBackoff());
        assertEquals(500, old.retryBackoff().delayMillis(500, 2, 0.8));
        var p = new ModelInvocationProperties();
        p.setTransportRetries(3); p.setRetryBaseMillis(3000); p.setRetryMaxMillis(20000);
        p.setRetryMultiplier(3); p.setRetryJitterRatio(0); p.setRetryCheckMillis(100);
        var frozen = InvocationPolicy.from(p);
        assertEquals(frozen, mapper.readValue(mapper.writeValueAsString(frozen), InvocationPolicy.class));
        p.setRetryMultiplier(1);
        assertEquals(3, frozen.properties().getRetryMultiplier());
        assertEquals(frozen.retryBackoff(), frozen.withTransportRetries(0).retryBackoff());
    }
}
