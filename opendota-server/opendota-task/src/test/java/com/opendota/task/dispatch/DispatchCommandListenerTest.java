package com.opendota.task.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.mqtt.publisher.MqttPublisher;
import com.opendota.mqtt.publisher.MqttPublishException;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.outbox.DispatchCommand;
import com.opendota.task.outbox.KafkaTopicProperties;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DispatchCommandListener 单元测试。
 */
class DispatchCommandListenerTest {

    private ObjectMapper objectMapper;
    private MqttPublisher mqttPublisher;
    private JdbcTemplate jdbcTemplate;
    private TaskDefinitionRepository taskDefRepo;
    private TaskDispatchRecordRepository dispatchRecordRepo;
    private DispatchRateLimiter rateLimiter;
    private TaskDispatchScheduler scheduler;
    private DispatchMetrics metrics;
    private DispatchProperties props;
    private KafkaTemplate<String, String> dlqKafka;
    private KafkaTopicProperties topicProps;
    private Acknowledgment ack;
    private DispatchCommandListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mqttPublisher = mock(MqttPublisher.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        taskDefRepo = mock(TaskDefinitionRepository.class);
        dispatchRecordRepo = mock(TaskDispatchRecordRepository.class);
        rateLimiter = mock(DispatchRateLimiter.class);
        // 使用立即执行的调度器,简化测试
        scheduler = spy(new TaskDispatchScheduler(1, 0));
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new DispatchMetrics(registry);
        props = new DispatchProperties();
        dlqKafka = mock(KafkaTemplate.class);
        topicProps = new KafkaTopicProperties();
        ack = mock(Acknowledgment.class);

        listener = new DispatchCommandListener(
                objectMapper, mqttPublisher, jdbcTemplate,
                taskDefRepo, dispatchRecordRepo,
                rateLimiter, scheduler, metrics, props,
                dlqKafka, topicProps);
    }

    @Test
    void onDispatchCommand_onlineVehicle_dispatchesMqtt() throws Exception {
        DispatchCommand cmd = buildCommand();
        TaskDefinition taskDef = buildTaskDefinition();
        TaskDispatchRecord record = buildDispatchRecord();

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(1); // 在线
        when(dispatchRecordRepo.findByTaskIdAndVin("tsk_001", "LSVWA234567890123"))
                .thenReturn(Optional.of(record));
        when(dispatchRecordRepo.countByTaskId("tsk_001")).thenReturn(10L);
        when(taskDefRepo.findByTaskId("tsk_001")).thenReturn(Optional.of(taskDef));
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyString())).thenReturn(true);

        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(
                "task-dispatch-queue", 0, 0L, "tsk_001", objectMapper.writeValueAsString(cmd));

        listener.onDispatchCommand(consumerRecord, ack);

        verify(mqttPublisher).publish(any(DiagMessage.class));
        verify(ack).acknowledge();
        assertEquals("dispatched", record.getDispatchStatus());
        assertNotNull(record.getDispatchedAt());
    }

    @Test
    void onDispatchCommand_offlineVehicle_marksPendingOnline() throws Exception {
        DispatchCommand cmd = buildCommand();
        TaskDispatchRecord record = buildDispatchRecord();

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0); // 离线
        when(dispatchRecordRepo.findByTaskIdAndVin("tsk_001", "LSVWA234567890123"))
                .thenReturn(Optional.of(record));

        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(
                "task-dispatch-queue", 0, 0L, "tsk_001", objectMapper.writeValueAsString(cmd));

        listener.onDispatchCommand(consumerRecord, ack);

        verify(mqttPublisher, never()).publish(any());
        verify(ack).acknowledge();
        assertEquals("pending_online", record.getDispatchStatus());
        assertEquals(1, metrics.pendingOnlineCounter().count());
    }

    @Test
    void onDispatchCommand_rateLimited_noStatusUpdate() throws Exception {
        DispatchCommand cmd = buildCommand();
        TaskDefinition taskDef = buildTaskDefinition();
        TaskDispatchRecord record = buildDispatchRecord();

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(1); // 在线
        when(dispatchRecordRepo.findByTaskIdAndVin("tsk_001", "LSVWA234567890123"))
                .thenReturn(Optional.of(record));
        when(dispatchRecordRepo.countByTaskId("tsk_001")).thenReturn(10L);
        when(taskDefRepo.findByTaskId("tsk_001")).thenReturn(Optional.of(taskDef));
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyString())).thenReturn(false);

        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(
                "task-dispatch-queue", 0, 0L, "tsk_001", objectMapper.writeValueAsString(cmd));

        listener.onDispatchCommand(consumerRecord, ack);

        verify(mqttPublisher, never()).publish(any());
        // 限流时仍 ack offset,让 Kafka 重投
        verify(ack).acknowledge();
        assertEquals(1, metrics.rateLimitedCounter().count());
    }

    @Test
    void onDispatchCommand_maxRetriesExceeded_sendsToDlq() throws Exception {
        DispatchCommand cmd = buildCommand();
        TaskDispatchRecord record = buildDispatchRecord();
        record.setRetryCount(5); // 达到上限

        when(dispatchRecordRepo.findByTaskIdAndVin("tsk_001", "LSVWA234567890123"))
                .thenReturn(Optional.of(record));

        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(
                "task-dispatch-queue", 0, 0L, "tsk_001", objectMapper.writeValueAsString(cmd));

        listener.onDispatchCommand(consumerRecord, ack);

        verify(dlqKafka).send(eq("task-dispatch-dlq"), eq("tsk_001"), anyString());
        verify(mqttPublisher, never()).publish(any());
        verify(ack).acknowledge();
        assertEquals(1, metrics.dlqCounter().count());
        assertEquals("failed", record.getDispatchStatus());
    }

    @Test
    void onDispatchCommand_mqttFailure_marksFailed() throws Exception {
        DispatchCommand cmd = buildCommand();
        TaskDefinition taskDef = buildTaskDefinition();
        TaskDispatchRecord record = buildDispatchRecord();

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(1);
        when(dispatchRecordRepo.findByTaskIdAndVin("tsk_001", "LSVWA234567890123"))
                .thenReturn(Optional.of(record));
        when(dispatchRecordRepo.countByTaskId("tsk_001")).thenReturn(10L);
        when(taskDefRepo.findByTaskId("tsk_001")).thenReturn(Optional.of(taskDef));
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyString())).thenReturn(true);
        doThrow(new MqttPublishException("MQTT 连接丢失")).when(mqttPublisher).publish(any());

        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(
                "task-dispatch-queue", 0, 0L, "tsk_001", objectMapper.writeValueAsString(cmd));

        listener.onDispatchCommand(consumerRecord, ack);

        verify(ack).acknowledge();
        assertEquals("failed", record.getDispatchStatus());
        assertNotNull(record.getLastError());
    }

    @Test
    void onDispatchCommand_invalidJson_skipsRecord() throws Exception {
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(
                "task-dispatch-queue", 0, 0L, "tsk_001", "not-json!!!");

        listener.onDispatchCommand(consumerRecord, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(mqttPublisher);
    }

    @Test
    void onDispatchCommand_taskNotFound_marksFailed() throws Exception {
        DispatchCommand cmd = buildCommand();

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(1);
        when(dispatchRecordRepo.findByTaskIdAndVin("tsk_001", "LSVWA234567890123"))
                .thenReturn(Optional.of(buildDispatchRecord()));
        when(dispatchRecordRepo.countByTaskId("tsk_001")).thenReturn(10L);
        when(taskDefRepo.findByTaskId("tsk_001")).thenReturn(Optional.empty());

        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>(
                "task-dispatch-queue", 0, 0L, "tsk_001", objectMapper.writeValueAsString(cmd));

        listener.onDispatchCommand(consumerRecord, ack);

        verify(mqttPublisher, never()).publish(any());
        verify(ack).acknowledge();
    }

    // --- 辅助方法 ---

    private DispatchCommand buildCommand() {
        return new DispatchCommand(
                "tsk_001", "tenant-1", "LSVWA234567890123",
                "[\"BMS\"]", 5, "periodic", "batch",
                "{\"ecuName\":\"BMS\",\"transport\":\"UDS_ON_CAN\",\"txId\":\"0x7E3\",\"rxId\":\"0x7EB\",\"strategy\":1,\"steps\":[{\"seqId\":1,\"type\":\"raw_uds\",\"data\":\"220101\",\"timeoutMs\":2000}]}",
                "abc123");
    }

    private TaskDefinition buildTaskDefinition() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskId("tsk_001");
        td.setTenantId("tenant-1");
        td.setTaskName("测试任务");
        td.setScheduleType("periodic");
        td.setScheduleConfig("{\"scheduleCondition\":{\"mode\":\"periodic\",\"cronExpression\":\"0 */5 * * * ?\",\"maxExecutions\":100}}");
        td.setDiagPayload("{\"ecuName\":\"BMS\",\"transport\":\"UDS_ON_CAN\",\"txId\":\"0x7E3\",\"rxId\":\"0x7EB\",\"strategy\":1,\"steps\":[{\"seqId\":1,\"type\":\"raw_uds\",\"data\":\"220101\",\"timeoutMs\":2000}]}");
        td.setPayloadHash("abc123");
        td.setPriority(5);
        td.setStatus("active");
        return td;
    }

    private TaskDispatchRecord buildDispatchRecord() {
        TaskDispatchRecord record = new TaskDispatchRecord();
        record.setTaskId("tsk_001");
        record.setTenantId("tenant-1");
        record.setVin("LSVWA234567890123");
        record.setEcuScope("[\"BMS\"]");
        record.setDispatchStatus("pending_online");
        record.setRetryCount(0);
        return record;
    }
}
