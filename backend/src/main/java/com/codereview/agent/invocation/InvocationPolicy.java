package com.codereview.agent.invocation;
/** Immutable per-task model limits; absent historical fields use the original defaults. */
public record InvocationPolicy(int transportRetries, long retryBaseMillis, int maxReadTimeoutSeconds,
                               int safetyReserveMinTokens, double safetyReserveRatio, RetryBackoff retryBackoff) {
    public InvocationPolicy {
        if (transportRetries < 0 || transportRetries > 10 || retryBaseMillis <= 0 || maxReadTimeoutSeconds <= 0
                || safetyReserveMinTokens <= 0 || !Double.isFinite(safetyReserveRatio)
                || safetyReserveRatio <= 0 || safetyReserveRatio >= 1) {
            throw new IllegalArgumentException("任务模型调用配置无效");
        }
        if (retryBackoff == null) retryBackoff = RetryBackoff.legacy();
    }
    public InvocationPolicy(int transportRetries, long retryBaseMillis, int maxReadTimeoutSeconds,
                            int safetyReserveMinTokens, double safetyReserveRatio) {
        this(transportRetries, retryBaseMillis, maxReadTimeoutSeconds,
                safetyReserveMinTokens, safetyReserveRatio, RetryBackoff.legacy());
    }
    public InvocationPolicy withTransportRetries(int retries) {
        return new InvocationPolicy(retries, retryBaseMillis, maxReadTimeoutSeconds,
                safetyReserveMinTokens, safetyReserveRatio, retryBackoff);
    }
    /** Version 2 tasks predate this node; keep their interpretation independent of future defaults. */
    public static InvocationPolicy defaults() { return new InvocationPolicy(1, 500, 180, 1024, 0.1); }
    public static InvocationPolicy from(ModelInvocationProperties p) {
        return new InvocationPolicy(p.getTransportRetries(), p.getRetryBaseMillis(), p.getMaxReadTimeoutSeconds(),
                p.getSafetyReserveMinTokens(), p.getSafetyReserveRatio(), RetryBackoff.from(p));
    }
    public ModelInvocationProperties properties() {
        ModelInvocationProperties p = new ModelInvocationProperties();
        p.setTransportRetries(transportRetries); p.setRetryBaseMillis(retryBaseMillis);
        p.setMaxReadTimeoutSeconds(maxReadTimeoutSeconds); p.setSafetyReserveMinTokens(safetyReserveMinTokens);
        p.setRetryMaxMillis(retryBackoff.maxMillis()); p.setRetryMultiplier(retryBackoff.multiplier());
        p.setRetryJitterRatio(retryBackoff.jitterRatio()); p.setRetryCheckMillis(retryBackoff.checkMillis());
        p.setSafetyReserveRatio(safetyReserveRatio); return p;
    }
}
