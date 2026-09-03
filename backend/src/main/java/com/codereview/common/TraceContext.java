package com.codereview.common;

/**
 * 请求链路 traceId（ThreadLocal）
 */
public class TraceContext {
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        String t = TRACE_ID.get();
        return t != null ? t : "unknown";
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
