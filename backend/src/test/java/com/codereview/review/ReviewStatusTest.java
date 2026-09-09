package com.codereview.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewStatusTest {

    @Test
    void allSuccess() {
        assertEquals(ReviewStatus.SUCCESS, ReviewStatus.resolve(3, 3, 0));
    }

    @Test
    void allFailed() {
        assertEquals(ReviewStatus.FAILED, ReviewStatus.resolve(3, 0, 3));
    }

    @Test
    void partial() {
        assertEquals(ReviewStatus.PARTIAL, ReviewStatus.resolve(3, 2, 1));
    }

    @Test
    void emptyTotalIsFailed() {
        assertEquals(ReviewStatus.FAILED, ReviewStatus.resolve(0, 0, 0));
    }
}
