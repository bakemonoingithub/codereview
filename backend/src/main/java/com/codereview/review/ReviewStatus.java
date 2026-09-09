package com.codereview.review;

/**
 * 审查记录状态：0 排队 / 1 执行中 / 2 成功 / 3 失败 / 4 部分成功。
 */
public final class ReviewStatus {

    public static final int QUEUED = 0;
    public static final int RUNNING = 1;
    public static final int SUCCESS = 2;
    public static final int FAILED = 3;
    public static final int PARTIAL = 4;

    private ReviewStatus() {
    }

    /** 由单元成功/失败数推导记录状态。 */
    public static int resolve(int total, int successCount, int failedCount) {
        if (total == 0) {
            return FAILED;
        }
        if (failedCount == 0) {
            return SUCCESS;
        }
        if (successCount == 0) {
            return FAILED;
        }
        return PARTIAL;
    }
}
