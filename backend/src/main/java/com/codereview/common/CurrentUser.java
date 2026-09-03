package com.codereview.common;

/**
 * 当前登录用户 id（ThreadLocal），登录拦截器设置，用于审计字段填充
 */
public class CurrentUser {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
