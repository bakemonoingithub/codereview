package com.codereview.agent.invocation;

/** Frozen local retry timing. A gateway Retry-After is never shortened by this cap. */
public record RetryBackoff(long maxMillis, double multiplier, double jitterRatio, long checkMillis) {
    public RetryBackoff {
        if (maxMillis <= 0 || !Double.isFinite(multiplier) || multiplier < 1 || multiplier > 10
                || !Double.isFinite(jitterRatio) || jitterRatio < 0 || jitterRatio > 1 || checkMillis <= 0) {
            throw new IllegalArgumentException("模型重试等待配置无效");
        }
    }

    /** Historical snapshots had fixed local delays and no jitter. */
    public static RetryBackoff legacy() { return new RetryBackoff(Long.MAX_VALUE, 1, 0, 1000); }

    public static RetryBackoff from(ModelInvocationProperties p) {
        return new RetryBackoff(p.getRetryMaxMillis(), p.getRetryMultiplier(),
                p.getRetryJitterRatio(), p.getRetryCheckMillis());
    }

    public long delayMillis(long baseMillis, int failedAttempt, double random) {
        double delay = Math.min(maxMillis, baseMillis * Math.pow(multiplier, failedAttempt - 1));
        double jitter = 1 + (2 * random - 1) * jitterRatio;
        return Math.max(1, (long) Math.min(maxMillis, delay * jitter));
    }
}
