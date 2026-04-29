package com.opendota.diag.cmd;

import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.single.SingleCmdPayload;
import com.opendota.diag.channel.ChannelContext;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.mqtt.publisher.MqttPublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SingleCmdServiceTest {

    private static final Operator OPERATOR = new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1");

    private DiagDispatcher dispatcher;
    private DiagRecordRepository diagRecordRepository;
    private DiagCmdMetrics metrics;
    private SingleCmdService service;

    @BeforeEach
    void setUp() {
        dispatcher = mock(DiagDispatcher.class);
        diagRecordRepository = mock(DiagRecordRepository.class);
        metrics = mock(DiagCmdMetrics.class);
        service = new SingleCmdService(dispatcher, diagRecordRepository, metrics);
    }

    @Test
    void dispatchShouldInsertPendingBeforePublish() {
        DiagDispatcher.PreparedSingleCmd prepared = prepared("550e8400-e29b-41d4-a716-446655440000");
        when(dispatcher.prepareSingleCmd("ch-1", "raw_uds", "22F190", 5000, OPERATOR)).thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedSingleCmd(prepared);

        String msgId = service.dispatch(new SingleCmdController.SingleCmdRequest("ch-1", "raw_uds", "22F190", 5000), OPERATOR);

        assertEquals("550e8400-e29b-41d4-a716-446655440000", msgId);
        verify(diagRecordRepository).insertPendingSingleCmd(any(DiagRecordRepository.PendingSingleCmd.class));
        verify(dispatcher).publishPreparedSingleCmd(prepared);
    }

    @Test
    void dispatchShouldDeletePendingIfPublishFails() {
        DiagDispatcher.PreparedSingleCmd prepared = prepared("550e8400-e29b-41d4-a716-446655440001");
        when(dispatcher.prepareSingleCmd("ch-1", "raw_uds", "22F190", 5000, OPERATOR)).thenReturn(prepared);
        doThrow(new MqttPublishException("MQTT publish 失败")).when(dispatcher).publishPreparedSingleCmd(prepared);

        try {
            service.dispatch(new SingleCmdController.SingleCmdRequest("ch-1", "raw_uds", "22F190", 5000), OPERATOR);
        } catch (MqttPublishException expected) {
            // expected
        }

        verify(diagRecordRepository).insertPendingSingleCmd(any(DiagRecordRepository.PendingSingleCmd.class));
        verify(dispatcher).publishPreparedSingleCmd(prepared);
        verify(diagRecordRepository).deleteByMsgId("550e8400-e29b-41d4-a716-446655440001");
        verifyNoMoreInteractions(diagRecordRepository);
    }

    private static DiagDispatcher.PreparedSingleCmd prepared(String msgId) {
        ChannelContext channelContext = new ChannelContext("ch-1", "LSVWA234567890123", "VCU", java.util.List.of("VCU"), OPERATOR);
        DiagMessage<SingleCmdPayload> envelope = new DiagMessage<>(
                msgId,
                System.currentTimeMillis(),
                "LSVWA234567890123",
                com.opendota.common.envelope.DiagAction.SINGLE_CMD,
                OPERATOR,
                null,
                new SingleCmdPayload(msgId, "ch-1", "VCU", "raw_uds", "22F190", 5000));
        return new DiagDispatcher.PreparedSingleCmd(channelContext, "raw_uds", envelope);
    }
}
