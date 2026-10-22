package com.codereview.agent.tools;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 只读工具的运行参数；上限常量仍在 {@link ToolLimits}，这里只放环境相关配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.tools")
public class ToolProperties {

    /** git 可执行文件；内容搜索要求宿主具备 git，未安装时工具返回可诊断失败而不是静默零命中。 */
    private String gitExecutable = "git";

    /** 共享镜像首次克隆或拉取的超时（秒）；与 git.gitea.mirror-timeout-seconds 同义。 */
    private int mirrorTimeoutSeconds = 120;

    /** 搜索等待槽位加执行的总时限（秒）；与任务剩余期限取较小值。 */
    private int searchTotalSeconds = ToolLimits.SEARCH_TOTAL_SECONDS;

    /** 搜索全局并发。 */
    private int searchGlobalConcurrency = ToolLimits.SEARCH_GLOBAL_CONCURRENCY;

    /** 搜索命中的缓存权重上限（字节）与过期时间（分钟）。 */
    private long searchCacheWeightBytes = 32L * 1024 * 1024;
    private int searchCacheMinutes = 5;
}
