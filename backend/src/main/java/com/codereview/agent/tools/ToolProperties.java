package com.codereview.agent.tools;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 只读工具的进程资源参数；任务工具限额由 ToolLimitProperties 单独绑定并冻结。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.tools")
public class ToolProperties {
    private ToolLimitProperties limits = new ToolLimitProperties();

    /** git 可执行文件；内容搜索要求宿主具备 git，未安装时工具返回可诊断失败而不是静默零命中。 */
    private String gitExecutable = "git";

    /** 搜索准备镜像的等待预算（秒）；仍受 git.gitea.mirror-timeout-seconds 限制。 */
    private int mirrorTimeoutSeconds = 120;

    /** 镜像准备、槽位等待与搜索执行的总时限（秒）；任务期限由执行守卫另行控制。 */
    private int searchTotalSeconds = ToolLimits.SEARCH_TOTAL_SECONDS;

    /** 搜索全局并发。 */
    private int searchGlobalConcurrency = ToolLimits.SEARCH_GLOBAL_CONCURRENCY;

    /** 搜索命中的缓存权重上限（字节）与过期时间（分钟）。 */
    private long searchCacheWeightBytes = 32L * 1024 * 1024;
    private int searchCacheMinutes = 5;
}
