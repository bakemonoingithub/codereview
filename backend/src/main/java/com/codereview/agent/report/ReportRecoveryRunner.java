package com.codereview.agent.report;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.support.AgentTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时收敛上次进程遗留的报告。
 *
 * <p>报告生成没有可恢复的执行现场；服务重启后 QUEUED／RUNNING 已经没有对应 worker，
 * 继续显示"生成中"会误导用户。这里把它们明确落为失败，保留冻结来源和错误，
 * 用户可重新选材生成新报告。
 */
@Component
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReportRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportRecoveryRunner.class);

    private final AgentDatabase db;

    public ReportRecoveryRunner(AgentDatabase db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        int changed = db.mapper(AgentReportMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReport>()
                        .in(AgentReport::getState, ReportStates.QUEUED, ReportStates.RUNNING)
                        .set(AgentReport::getState, ReportStates.FAILED)
                        .set(AgentReport::getErrorCode, "REPORT_INTERRUPTED")
                        .set(AgentReport::getErrorMessage, "服务重启，报告生成已中断；冻结来源保留")
                        .set(AgentReport::getFinishedAt, AgentTime.now())
                        .set(AgentReport::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
        if (changed > 0) {
            log.warn("启动收敛：{} 个未完成报告已标记为失败", changed);
        }
    }
}
