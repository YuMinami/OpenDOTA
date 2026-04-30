package com.opendota.task.execution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 周期任务 execution 对账作业(协议 §8.5.1)。
 *
 * <p>定时扫描 task_execution_log 中 begin 后超过 maxExecutionMs * 3 仍无 end 的记录,
 * 标记为 failed 并触发告警。
 *
 * <p>扫描频率:每 60 秒一次。
 * <p>超时倍数:3 倍 maxExecutionMs(留足车端执行 + 网络延迟余量)。
 */
@Component
public class ExecutionReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReconcileJob.class);

    private final TaskExecutionLogRepository execLogRepo;
    private final JdbcTemplate jdbc;
    private final Counter timeoutCounter;

    public ExecutionReconcileJob(TaskExecutionLogRepository execLogRepo,
                                  JdbcTemplate jdbc,
                                  MeterRegistry meterRegistry) {
        this.execLogRepo = execLogRepo;
        this.jdbc = jdbc;
        this.timeoutCounter = Counter.builder("dota_execution_timeout_total")
                .description("execution_begin 后超时未收到 end 的次数")
                .register(meterRegistry);
    }

    /**
     * 每 60 秒扫描一次超时的 execution_begin 记录。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reconcile() {
        List<TaskExecutionLogRepository.ExecutionBeginRecord> timedOut = execLogRepo.findTimedOutExecutions();
        if (timedOut.isEmpty()) {
            return;
        }

        log.warn("发现 {} 条超时未结束的 execution_begin,开始标记 failed", timedOut.size());

        for (TaskExecutionLogRepository.ExecutionBeginRecord record : timedOut) {
            try {
                int affected = execLogRepo.markTimedOutAsFailed(
                        record.taskId(), record.vin(), record.executionSeq(), record.tenantId());
                if (affected > 0) {
                    timeoutCounter.increment();
                    log.error("execution 超时标记 failed: taskId={} vin={} seq={} beginAt={}",
                            record.taskId(), record.vin(), record.executionSeq(), record.beginReportedAt());
                }
            } catch (Exception e) {
                log.error("标记 execution 超时失败: taskId={} vin={} seq={}",
                        record.taskId(), record.vin(), record.executionSeq(), e);
            }
        }
    }
}
