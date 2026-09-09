package com.codereview.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    @Test
    void emptyOrNull() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void roundsUp() {
        assertEquals(2, TokenEstimator.estimate("1234"));     // 4/3.5=1.14 → 2
        assertEquals(2, TokenEstimator.estimate("1234567"));  // 7/3.5=2.0  → 2
        assertEquals(3, TokenEstimator.estimate("12345678")); // 8/3.5=2.29 → 3
    }
}
