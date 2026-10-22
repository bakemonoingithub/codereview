package com.codereview.agent.support;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 新库统一用 UTC 存储；接口输出时前端按本地时区展示。 */
public final class AgentTime {

    private AgentTime() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
