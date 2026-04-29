package com.opendota.diag.cmd;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.common.EcuBlock;
import com.opendota.common.payload.common.ScriptDiagPayload;
import com.opendota.common.payload.common.Step;
import com.opendota.common.payload.common.Transport;
import com.opendota.diag.arbitration.EcuLockRegistry;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.mqtt.publisher.MqttPublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ScriptCmdServiceTest {

    private static final Operator OPERATOR = new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1");

    private DiagDispatcher dispatcher;
    private DiagRecordRepository diagRecordRepository;
    private DiagCmdMetrics metrics;
    private EcuLockRegistry ecuLockRegistry;
    private ScriptCmdService service;

    @BeforeEach
    void setUp() {
        dispatcher = mock(DiagDispatcher.class);
        diagRecordRepository = mock(DiagRecordRepository.class);
        metrics = mock(DiagCmdMetrics.class);
        ecuLockRegistry = mock(EcuLockRegistry.class);
        service = new ScriptCmdService(dispatcher, diagRecordRepository, metrics, ecuLockRegistry);
    }

    @Test
    void dispatchShouldInsertPendingBeforePublish() {
        ScriptCmdController.ScriptCmdRequest request = buildRequest();
        DiagDispatcher.PreparedScriptCmd prepared = prepared("550e8400-e29b-41d4-a716-446655440099");

        List<EcuLockRegistry.AcquiredLock> locks = List.of(
                new EcuLockRegistry.AcquiredLock("BMS", "ecu:lock:LSVWA234567890123:BMS", null),
                new EcuLockRegistry.AcquiredLock("GW", "ecu:lock:LSVWA234567890123:GW", null),
                new EcuLockRegistry.AcquiredLock("VCU", "ecu:lock:LSVWA234567890123:VCU", null));
        when(ecuLockRegistry.tryAcquireAll("LSVWA234567890123", List.of("BMS", "GW", "VCU"))).thenReturn(locks);
        when(dispatcher.prepareScriptCmd(
                eq("LSVWA234567890123"), eq("script-001"), eq("parallel"),
                eq(30000), eq(5), any(), eq(OPERATOR))).thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedScriptCmd(prepared);

        String msgId = service.dispatch(request, OPERATOR);

        assertEquals("550e8400-e29b-41d4-a716-446655440099", msgId);
        verify(diagRecordRepository).insertPendingScriptCmd(any(DiagRecordRepository.PendingScriptCmd.class));
        verify(dispatcher).publishPreparedScriptCmd(prepared);
        verify(ecuLockRegistry).releaseAll(locks);
    }

    @Test
    void dispatchShouldDeletePendingIfPublishFails() {
        ScriptCmdController.ScriptCmdRequest request = buildRequest();
        DiagDispatcher.PreparedScriptCmd prepared = prepared("550e8400-e29b-41d4-a716-446655440098");

        List<EcuLockRegistry.AcquiredLock> locks = List.of(
                new EcuLockRegistry.AcquiredLock("BMS", "ecu:lock:LSVWA234567890123:BMS", null),
                new EcuLockRegistry.AcquiredLock("GW", "ecu:lock:LSVWA234567890123:GW", null),
                new EcuLockRegistry.AcquiredLock("VCU", "ecu:lock:LSVWA234567890123:VCU", null));
        when(ecuLockRegistry.tryAcquireAll("LSVWA234567890123", List.of("BMS", "GW", "VCU"))).thenReturn(locks);
        when(dispatcher.prepareScriptCmd(
                eq("LSVWA234567890123"), eq("script-001"), eq("parallel"),
                eq(30000), eq(5), any(), eq(OPERATOR))).thenReturn(prepared);
        doThrow(new MqttPublishException("MQTT publish 失败")).when(dispatcher).publishPreparedScriptCmd(prepared);

        assertThrows(MqttPublishException.class, () -> service.dispatch(request, OPERATOR));

        verify(diagRecordRepository).insertPendingScriptCmd(any(DiagRecordRepository.PendingScriptCmd.class));
        verify(dispatcher).publishPreparedScriptCmd(prepared);
        verify(diagRecordRepository).deleteByMsgId("550e8400-e29b-41d4-a716-446655440098");
        verify(ecuLockRegistry).releaseAll(locks);
        verifyNoMoreInteractions(diagRecordRepository);
    }

    @Test
    void aggregateEcuScopeShouldDeduplicateAndSort() {
        List<EcuBlock> ecus = List.of(
                new EcuBlock("VCU", List.of("VCU", "GW"), Transport.UDS_ON_CAN, "0x7E0", "0x7E8", null, 1, List.of(
                        new Step(1, "raw_uds", "22F190", 5000, null))),
                new EcuBlock("BMS", List.of("BMS", "GW"), Transport.UDS_ON_CAN, "0x641", "0x642", null, 1, List.of(
                        new Step(1, "raw_uds", "22F190", 5000, null))));

        List<String> scope = ScriptCmdService.aggregateEcuScope(ecus);

        assertEquals(List.of("BMS", "GW", "VCU"), scope);
    }

    private static ScriptCmdController.ScriptCmdRequest buildRequest() {
        List<EcuBlock> ecus = List.of(
                new EcuBlock("BMS", List.of("BMS", "GW"), Transport.UDS_ON_CAN, "0x641", "0x642", null, 1, List.of(
                        new Step(1, "raw_uds", "22F190", 5000, null))),
                new EcuBlock("VCU", List.of("VCU"), Transport.UDS_ON_CAN, "0x7E0", "0x7E8", null, 1, List.of(
                        new Step(1, "raw_uds", "22F190", 5000, null))));
        return new ScriptCmdController.ScriptCmdRequest(
                "LSVWA234567890123", "script-001", "parallel", 30000, 5, ecus);
    }

    private static DiagDispatcher.PreparedScriptCmd prepared(String msgId) {
        List<EcuBlock> ecus = List.of(
                new EcuBlock("BMS", List.of("BMS", "GW"), Transport.UDS_ON_CAN, "0x641", "0x642", null, 1, List.of(
                        new Step(1, "raw_uds", "22F190", 5000, null))),
                new EcuBlock("VCU", List.of("VCU"), Transport.UDS_ON_CAN, "0x7E0", "0x7E8", null, 1, List.of(
                        new Step(1, "raw_uds", "22F190", 5000, null))));
        ScriptDiagPayload payload = new ScriptDiagPayload(
                "script", "script-001", "parallel", 30000, 5, ecus);
        DiagMessage<ScriptDiagPayload> envelope = new DiagMessage<>(
                msgId,
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.SCRIPT_CMD,
                OPERATOR,
                null,
                payload);
        return new DiagDispatcher.PreparedScriptCmd(envelope);
    }
}
