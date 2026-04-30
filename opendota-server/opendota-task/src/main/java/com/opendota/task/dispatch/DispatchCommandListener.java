package com.opendota.task.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.mqtt.publisher.MqttPublisher;
import com.opendota.mqtt.publisher.MqttPublishException;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.outbox.DispatchCommand;
import com.opendota.task.outbox.KafkaTopicProperties;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 分发指令 Kafka 消费者(架构 §4.4.1)。
 *
 * <p>消费 {@code task-dispatch-queue} topic 的 {@link DispatchCommand},
 * 根据车辆在线状态决定:
 * <ul>
 *   <li>在线:经限流 + jitter 调度后下发 MQTT {@code schedule_set}</li>
 *   <li>离线:保持 {@code pending_online} 状态,等 {@code OnlineEventConsumer} 触发回放</li>
 * </ul>
 *
 * <p>重试超限的命令被转发到 {@code task-dispatch-dlq} 死信队列。
 */
@Component
public class DispatchCommandListener {

    private static final Logger log = LoggerFactory.getLogger(DispatchCommandListener.class);

    private final ObjectMapper objectMapper;
    private final MqttPublisher mqttPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final TaskDefinitionRepository taskDefRepo;
    private final TaskDispatchRecordRepository dispatchRecordRepo;
    private final DispatchRateLimiter rateLimiter;
    private final TaskDispatchScheduler scheduler;
    private final DispatchMetrics metrics;
    private final DispatchProperties props;
    private final KafkaTemplate<String, String> dlqKafka;
    private final KafkaTopicProperties topicProps;

    public DispatchCommandListener(ObjectMapper objectMapper,
                                   MqttPublisher mqttPublisher,
                                   JdbcTemplate jdbcTemplate,
                                   TaskDefinitionRepository taskDefRepo,
                                   TaskDispatchRecordRepository dispatchRecordRepo,
                                   DispatchRateLimiter rateLimiter,
                                   TaskDispatchScheduler scheduler,
                                   DispatchMetrics metrics,
                                   DispatchProperties props,
                                   KafkaTemplate<String, String> dlqKafka,
                                   KafkaTopicProperties topicProps) {
        this.objectMapper = objectMapper;
        this.mqttPublisher = mqttPublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.taskDefRepo = taskDefRepo;
        this.dispatchRecordRepo = dispatchRecordRepo;
        this.rateLimiter = rateLimiter;
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.props = props;
        this.dlqKafka = dlqKafka;
        this.topicProps = topicProps;
    }

    @KafkaListener(
            topics = "${opendota.kafka.topic.dispatch-queue}",
            groupId = "opendota-dispatch-worker",
            containerFactory = "dispatchKafkaListenerContainerFactory"
    )
    public void onDispatchCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Timer.Sample sample = metrics.startLatency();
        DispatchCommand cmd;
        try {
            cmd = objectMapper.readValue(record.value(), DispatchCommand.class);
        } catch (JsonProcessingException e) {
            log.error("反序列化 DispatchCommand 失败,跳过 offset={}: {}", record.offset(), e.getMessage());
            ack.acknowledge();
            return;
        }

        MDC.put("taskId", cmd.taskId());
        MDC.put("vin", cmd.vin());
        MDC.put("tenantId", cmd.tenantId());
        try {
            processCommand(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("处理分发指令异常 taskId={} vin={}: {}", cmd.taskId(), cmd.vin(), e.getMessage(), e);
            ack.acknowledge();
        } finally {
            metrics.recordLatency(sample);
            MDC.remove("taskId");
            MDC.remove("vin");
            MDC.remove("tenantId");
        }
    }

    private void processCommand(DispatchCommand cmd) {
        metrics.recordDispatched();

        // 1. 检查重试次数,超限进 DLQ
        Optional<TaskDispatchRecord> recordOpt = dispatchRecordRepo
                .findByTaskIdAndVin(cmd.taskId(), cmd.vin());
        if (recordOpt.isPresent()) {
            TaskDispatchRecord record = recordOpt.get();
            if (record.getRetryCount() != null && record.getRetryCount() >= props.getMaxRetries()) {
                sendToDlq(cmd, "重试次数超限: " + record.getRetryCount());
                return;
            }
        }

        // 2. 查询车辆在线状态
        boolean online = isVehicleOnline(cmd.vin());

        if (online) {
            // 3. 在线:限流检查 → 调度下发
            DispatchSource source = resolveSource(cmd, true);
            scheduler.schedule(cmd.vin(), () -> dispatchOnline(cmd, source), source);
        } else {
            // 4. 离线:保持 pending_online
            markPendingOnline(cmd);
        }
    }

    private void dispatchOnline(DispatchCommand cmd, DispatchSource source) {
        // 限流检查
        if (!rateLimiter.tryAcquire(cmd.tenantId(), cmd.priority(), cmd.vin())) {
            log.debug("限流拒绝,等待重试 taskId={} vin={}", cmd.taskId(), cmd.vin());
            metrics.recordRateLimited();
            // 不更新状态,不 commit offset,等 Kafka 重投
            return;
        }

        // 构建 schedule_set envelope
        DiagMessage<JsonNode> envelope = buildScheduleSetEnvelope(cmd);
        if (envelope == null) {
            log.error("构建 schedule_set envelope 失败,跳过 taskId={}", cmd.taskId());
            return;
        }

        // MQTT publish
        try {
            mqttPublisher.publish(envelope);
            metrics.recordMqttPublished();
            markDispatched(cmd);
            log.info("schedule_set 下发成功 taskId={} vin={} source={}", cmd.taskId(), cmd.vin(), source);
        } catch (MqttPublishException e) {
            log.error("MQTT publish 失败 taskId={} vin={}: {}", cmd.taskId(), cmd.vin(), e.getMessage());
            markFailed(cmd, e.getMessage());
        }
    }

    /**
     * 构建 schedule_set 的 {@link DiagMessage} 信封。
     *
     * <p>从 TaskDefinition 取 scheduleConfig 和 diagPayload,
     * 组装成协议 §8.2 定义的 schedule_set payload。
     */
    private DiagMessage<JsonNode> buildScheduleSetEnvelope(DispatchCommand cmd) {
        try {
            // 从 task_definition 获取完整调度配置
            Optional<TaskDefinition> taskOpt = taskDefRepo.findByTaskId(cmd.taskId());
            if (taskOpt.isEmpty()) {
                log.error("task_definition 不存在 taskId={}", cmd.taskId());
                return null;
            }
            TaskDefinition taskDef = taskOpt.get();

            // 组装 schedule_set payload
            JsonNode scheduleConfig = objectMapper.readTree(taskDef.getScheduleConfig());
            JsonNode diagPayload = objectMapper.readTree(taskDef.getDiagPayload());

            // 合并: schedule_set payload = scheduleConfig 中的字段 + diagPayload 中的步骤
            var payloadNode = objectMapper.createObjectNode();
            payloadNode.put("taskId", cmd.taskId());
            payloadNode.put("priority", cmd.priority());

            // scheduleCondition 来自 scheduleConfig
            if (scheduleConfig.has("scheduleCondition")) {
                payloadNode.set("scheduleCondition", scheduleConfig.get("scheduleCondition"));
            }
            // 兼容:如果 scheduleConfig 本身就是 scheduleCondition 结构
            if (scheduleConfig.has("mode")) {
                payloadNode.set("scheduleCondition", scheduleConfig);
            }

            // ecuScope
            JsonNode ecuScopeNode = objectMapper.readTree(cmd.ecuScope());
            payloadNode.set("ecuScope", ecuScopeNode);

            // 从 diagPayload 提取传输配置和步骤
            if (diagPayload.has("ecus")) {
                // script_cmd 格式:取第一个 ecu 的传输配置
                JsonNode firstEcu = diagPayload.get("ecus").get(0);
                if (firstEcu != null) {
                    copyIfPresent(firstEcu, payloadNode, "ecuName");
                    copyIfPresent(firstEcu, payloadNode, "transport");
                    copyIfPresent(firstEcu, payloadNode, "txId");
                    copyIfPresent(firstEcu, payloadNode, "rxId");
                    copyIfPresent(firstEcu, payloadNode, "doipConfig");
                    copyIfPresent(firstEcu, payloadNode, "strategy");
                    copyIfPresent(firstEcu, payloadNode, "steps");
                }
            } else {
                // batch_cmd 格式
                copyIfPresent(diagPayload, payloadNode, "ecuName");
                copyIfPresent(diagPayload, payloadNode, "transport");
                copyIfPresent(diagPayload, payloadNode, "txId");
                copyIfPresent(diagPayload, payloadNode, "rxId");
                copyIfPresent(diagPayload, payloadNode, "doipConfig");
                copyIfPresent(diagPayload, payloadNode, "strategy");
                copyIfPresent(diagPayload, payloadNode, "steps");
            }

            // supersedes
            if (taskDef.getSupersedesTaskId() != null) {
                payloadNode.put("supersedes", taskDef.getSupersedesTaskId());
            }

            Operator operator = Operator.system(cmd.tenantId());
            return DiagMessage.c2v(cmd.vin(), DiagAction.SCHEDULE_SET, operator, payloadNode);
        } catch (JsonProcessingException e) {
            log.error("JSON 解析失败 taskId={}: {}", cmd.taskId(), e.getMessage());
            return null;
        }
    }

    private void copyIfPresent(JsonNode source, com.fasterxml.jackson.databind.node.ObjectNode target, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field));
        }
    }

    /**
     * 根据任务总目标数决定分发来源(REST §6.1 dispatchMode 分层 SLO)。
     */
    private DispatchSource resolveSource(DispatchCommand cmd, boolean online) {
        if (!online) {
            return DispatchSource.OFFLINE_REPLAY;
        }
        // 通过 dispatch_record 总数推断批量规模
        long totalTargets = dispatchRecordRepo.countByTaskId(cmd.taskId());
        if (totalTargets > 10_000) {
            return DispatchSource.BATCH_THROTTLED;
        } else if (totalTargets > 100) {
            return DispatchSource.BATCH_EAGER_ONLINE;
        }
        return DispatchSource.ONLINE_IMMEDIATE;
    }

    private boolean isVehicleOnline(String vin) {
        // 免 RLS:直接 JDBC 查询,不走 JPA
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vehicle_online_status WHERE vin = ? AND is_online = TRUE",
                Integer.class, vin);
        return count != null && count > 0;
    }

    private void markDispatched(DispatchCommand cmd) {
        dispatchRecordRepo.findByTaskIdAndVin(cmd.taskId(), cmd.vin()).ifPresent(record -> {
            record.setDispatchStatus("dispatched");
            record.setDispatchedAt(LocalDateTime.now());
            record.setRetryCount(record.getRetryCount() + 1);
            dispatchRecordRepo.save(record);
        });
    }

    private void markPendingOnline(DispatchCommand cmd) {
        metrics.recordPendingOnline();
        log.info("车辆离线,保持 pending_online taskId={} vin={}", cmd.taskId(), cmd.vin());
        dispatchRecordRepo.findByTaskIdAndVin(cmd.taskId(), cmd.vin()).ifPresent(record -> {
            record.setRetryCount(record.getRetryCount() + 1);
            // Phase 4 DoD #3: 首次进入 pending_online 时记录基准时间戳,
            // PendingTaskReplayService 回放成功时按 now() - pending_online_at 打点 offline_task_push_latency
            if (record.getPendingOnlineAt() == null) {
                record.setPendingOnlineAt(LocalDateTime.now());
            }
            dispatchRecordRepo.save(record);
        });
    }

    private void markFailed(DispatchCommand cmd, String error) {
        dispatchRecordRepo.findByTaskIdAndVin(cmd.taskId(), cmd.vin()).ifPresent(record -> {
            record.setDispatchStatus("failed");
            record.setLastError(truncate(error, 128));
            record.setRetryCount(record.getRetryCount() + 1);
            dispatchRecordRepo.save(record);
        });
    }

    private void sendToDlq(DispatchCommand cmd, String reason) {
        try {
            String payload = objectMapper.writeValueAsString(cmd);
            dlqKafka.send(topicProps.getDlq(), cmd.taskId(), payload);
            metrics.recordDlq();
            log.warn("分发指令进入 DLQ taskId={} vin={} 原因={}", cmd.taskId(), cmd.vin(), reason);

            // 标记 dispatch_status = failed
            dispatchRecordRepo.findByTaskIdAndVin(cmd.taskId(), cmd.vin()).ifPresent(record -> {
                record.setDispatchStatus("failed");
                record.setLastError(truncate("DLQ: " + reason, 128));
                dispatchRecordRepo.save(record);
            });
        } catch (JsonProcessingException e) {
            log.error("序列化 DLQ 消息失败 taskId={}", cmd.taskId());
        }
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
