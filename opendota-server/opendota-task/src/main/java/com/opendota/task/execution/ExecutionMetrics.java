package com.opendota.task.execution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 周期执行双 ack 相关 Prometheus 指标。
 */
@Component
public class ExecutionMetrics {

    private final Counter beginCounter;
    private final Counter endCounter;
    private final Counter endFailedCounter;

    public ExecutionMetrics(MeterRegistry meterRegistry) {
        this.beginCounter = Counter.builder("dota_execution_begin_total")
                .description("收到的 execution_begin 总数")
                .register(meterRegistry);

        this.endCounter = Counter.builder("dota_execution_end_total")
                .description("收到的 execution_end 总数")
                .tag("status", "success")
                .register(meterRegistry);

        this.endFailedCounter = Counter.builder("dota_execution_end_total")
                .description("收到的 execution_end 总数")
                .tag("status", "failed")
                .register(meterRegistry);
    }

    public void recordBegin(String taskId, String triggerSource) {
        beginCounter.increment();
    }

    public void recordEnd(String taskId, int overallStatus) {
        if (overallStatus == 0) {
            endCounter.increment();
        } else {
            endFailedCounter.increment();
        }
    }

    // --- 测试用访问器 ---

    public Counter beginCounter() { return beginCounter; }
    public Counter endCounter() { return endCounter; }
    public Counter endFailedCounter() { return endFailedCounter; }
}
