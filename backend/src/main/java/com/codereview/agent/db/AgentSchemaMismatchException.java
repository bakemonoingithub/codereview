package com.codereview.agent.db;

/**
 * 新库身份检查失败。
 *
 * <p>这是启动期错误，不交给 HTTP 层兜底：库连错、旧链混入或标记缺失时，
 * 宁可让新库组件启动失败，也不能带着错误的库继续跑。
 */
public class AgentSchemaMismatchException extends RuntimeException {

    public AgentSchemaMismatchException(String message) {
        super(message);
    }
}
