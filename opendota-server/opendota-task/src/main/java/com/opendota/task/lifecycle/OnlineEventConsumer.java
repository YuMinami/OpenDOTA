package com.opendota.task.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.outbox.DispatchCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 车辆生命周期 Kafka 消费者(架构 §4.6.3)。
 *
 * <p>消费 EMQX Rule Engine 推送到 {@code vehicle-lifecycle} topic 的 connected/disconnected 事件,
 * 更新 {@code vehicle_online_status} 表,并在车辆上线时触发 {@code pending_online} 任务回放。
 *
 * <p>回放逻辑委托给 {@link PendingTaskReplayService},查询 {@code dispatch_status='pending_online'}
 * 的分发记录,转换为 {@link DispatchCommand} 发布到 {@code task-dispatch-queue},
 * 由 {@link com.opendota.task.dispatch.DispatchCommandListener} 统一处理限流和 jitter 调度。
 *
 * <p>Kafka 分区按 VIN 哈希,确保同一辆车的 connected/disconnected 事件有序处理。
 */
@Component
public class OnlineEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OnlineEventConsumer.class);

    private final VehicleOnlineService vehicleOnlineService;
    private final PendingTaskReplayService replayService;
    private final ObjectMapper objectMapper;
    private final LifecycleMetrics metrics;

    public OnlineEventConsumer(VehicleOnlineService vehicleOnlineService,
                               PendingTaskReplayService replayService,
                               ObjectMapper objectMapper,
                               LifecycleMetrics metrics) {
        this.vehicleOnlineService = vehicleOnlineService;
        this.replayService = replayService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${opendota.kafka.topic.vehicle-lifecycle}",
            groupId = "opendota-online-status-consumer",
            containerFactory = "lifecycleKafkaListenerContainerFactory"
    )
    public void onLifecycleEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        VehicleLifecycleEvent event;
        try {
            event = objectMapper.readValue(record.value(), VehicleLifecycleEvent.class);
        } catch (JsonProcessingException e) {
            log.error("反序列化 VehicleLifecycleEvent 失败,跳过 offset={}: {}", record.offset(), e.getMessage());
            ack.acknowledge();
            return;
        }

        MDC.put("vin", event.vin());
        try {
            if (event.isConnected()) {
                handleConnected(event);
            } else if (event.isDisconnected()) {
                handleDisconnected(event);
            } else {
                log.warn("未知生命周期事件类型 event={} vin={}", event.event(), event.vin());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("处理生命周期事件异常 vin={} event={}: {}", event.vin(), event.event(), e.getMessage(), e);
            ack.acknowledge();
        } finally {
            MDC.remove("vin");
        }
    }

    private void handleConnected(VehicleLifecycleEvent event) {
        metrics.recordConnected();

        Instant onlineAt = Instant.ofEpochMilli(event.at());
        int updated = vehicleOnlineService.markOnline(event.vin(), event.clientId(), onlineAt);

        if (updated > 0) {
            // 状态变更:从离线→上线,触发 pending_online 回放
            log.info("车辆上线(状态变更),触发 pending_online 回放 vin={}", event.vin());
            replayService.replayPendingTasks(event.vin());
        } else {
            log.debug("车辆已在线,忽略重复 connected 事件 vin={}", event.vin());
        }
    }

    private void handleDisconnected(VehicleLifecycleEvent event) {
        metrics.recordDisconnected();

        Instant offlineAt = Instant.ofEpochMilli(event.at());
        int updated = vehicleOnlineService.markOffline(event.vin(), offlineAt);

        if (updated > 0) {
            log.info("车辆离线(状态变更) vin={}", event.vin());
        } else {
            log.debug("车辆已离线,忽略重复 disconnected 事件 vin={}", event.vin());
        }
    }
}
