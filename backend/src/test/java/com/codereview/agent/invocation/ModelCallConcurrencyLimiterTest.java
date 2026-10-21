package com.codereview.agent.invocation;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.support.AgentTime;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCallConcurrencyLimiterTest {

    @Test
    void deadlineBetweenGlobalAndTaskPermitsReleasesTheGlobalPermit() {
        var now = AgentTime.now();
        ModelCallConcurrencyLimiter limiter = new ModelCallConcurrencyLimiter(new ModelInvocationProperties());
        try (var time = org.mockito.Mockito.mockStatic(AgentTime.class)) {
            time.when(AgentTime::now).thenReturn(now, now.plusSeconds(2));
            ApiException error = assertThrows(ApiException.class,
                    () -> limiter.acquire("task-a", now.plusSeconds(1)));
            assertEquals(ErrorCode.TASK_DEADLINE, error.errorCode());
        }
        assertEquals(4, limiter.availableGlobalPermits());
    }

    @Test
    void globalLimitIsFourAndPerTaskLimitIsTwo() throws Exception {
        ModelInvocationProperties properties = new ModelInvocationProperties();
        ModelCallConcurrencyLimiter limiter = new ModelCallConcurrencyLimiter(properties);
        LocalDateTime deadline = AgentTime.now().plusMinutes(1);

        ModelCallConcurrencyLimiter.Permit a = limiter.acquire("task-a", deadline);
        ModelCallConcurrencyLimiter.Permit b = limiter.acquire("task-a", deadline);
        assertEquals(2, limiter.availableGlobalPermits());

        ApiException third = assertThrows(ApiException.class,
                () -> limiter.acquire("task-a", AgentTime.now().plusNanos(80_000_000)));
        assertEquals(ErrorCode.TASK_DEADLINE, third.errorCode());

        ModelCallConcurrencyLimiter.Permit c = limiter.acquire("task-b", deadline);
        ModelCallConcurrencyLimiter.Permit d = limiter.acquire("task-b", deadline);
        assertEquals(0, limiter.availableGlobalPermits());

        ApiException fifth = assertThrows(ApiException.class,
                () -> limiter.acquire("task-c", AgentTime.now().plusNanos(80_000_000)));
        assertEquals(ErrorCode.TASK_DEADLINE, fifth.errorCode());

        c.close();
        d.close();
        a.close();
        b.close();
        assertEquals(4, limiter.availableGlobalPermits());
    }

    @Test
    void expiredDeadlineDoesNotWaitForAPermit() {
        ModelCallConcurrencyLimiter limiter = new ModelCallConcurrencyLimiter(new ModelInvocationProperties());

        ApiException error = assertThrows(ApiException.class,
                () -> limiter.acquire("task-a", AgentTime.now().minusSeconds(1)));

        assertEquals(ErrorCode.TASK_DEADLINE, error.errorCode());
        assertEquals(4, limiter.availableGlobalPermits());
    }

    @Test
    void releasingTwiceIsSafe() {
        ModelCallConcurrencyLimiter limiter = new ModelCallConcurrencyLimiter(new ModelInvocationProperties());
        ModelCallConcurrencyLimiter.Permit permit = limiter.acquire(null, AgentTime.now().plusMinutes(1));

        permit.close();
        permit.close();

        assertEquals(4, limiter.availableGlobalPermits());
        assertTrue(true);
    }
}
