package com.codereview.agent.tools;

/**
 * 工具参数或作用域校验失败。
 *
 * <p>这类失败要作为 {@code INVALID_ARGUMENT} 工具结果交回模型自行修正，
 * 不把整个任务判为失败；只有存储、项目权限等任务级问题才向上抛出。
 */
public class ToolFailure extends RuntimeException {

    private final String status;
    private final String code;

    public ToolFailure(String status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ToolFailure invalid(String code, String message) {
        return new ToolFailure(ToolEnvelope.INVALID_ARGUMENT, code, message);
    }

    public static ToolFailure notFound(String message) {
        return new ToolFailure(ToolEnvelope.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ToolFailure unreadable(String code, String message) {
        return new ToolFailure(ToolEnvelope.UNREADABLE, code, message);
    }

    public static ToolFailure timeout(String message) {
        return new ToolFailure(ToolEnvelope.TIMEOUT, "TIMEOUT", message);
    }
}
