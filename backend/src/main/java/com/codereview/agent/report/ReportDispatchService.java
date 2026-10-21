package com.codereview.agent.report;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.support.AgentTime;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报告生成调度：有界并发，入队失败立即落为失败状态。
 *
 * <p>报告与专项共用公共模型并发许可；这里只限制后台 worker 数量，避免大量报告同时
 * 占用等待许可的线程。状态更新全部带前置状态条件，重复调度不会重跑已开始或已结束的报告。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReportDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ReportDispatchService.class);

    private final AgentDatabase db;
    private final ReportExecutionWorker worker;
    private final ExecutorService pool;

    public ReportDispatchService(AgentDatabase db, ReportExecutionWorker worker,
                                 ReportGenerationProperties properties) {
        this.db = db;
        this.worker = worker;
        int concurrency = Math.max(1, properties.getDispatchConcurrency());
        this.pool = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getDispatchQueueCapacity())),
                new NamedThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public void dispatch(String reportId) {
        try {
            pool.execute(() -> {
                try {
                    worker.execute(reportId);
                } catch (RuntimeException e) {
                    log.error("报告 {} 调度执行异常", reportId, e);
                    terminateActive(reportId, ErrorCode.INTERNAL_ERROR.code(), "报告后台执行异常");
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("报告调度队列已满，报告 {} 标记失败", reportId);
            terminateActive(reportId, ErrorCode.INTERNAL_ERROR.code(), "报告调度不可用或队列已满");
        }
    }

    private void terminateActive(String reportId, String code, String message) {
        db.mapper(AgentReportMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReport>()
                        .eq(AgentReport::getId, reportId)
                        .in(AgentReport::getState, ReportStates.QUEUED, ReportStates.RUNNING)
                        .set(AgentReport::getState, ReportStates.FAILED)
                        .set(AgentReport::getErrorCode, code)
                        .set(AgentReport::getErrorMessage, message)
                        .set(AgentReport::getFinishedAt, AgentTime.now())
                        .set(AgentReport::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-report-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
