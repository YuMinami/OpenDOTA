package com.opendota.diag.cmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.common.BatchDiagPayload;
import com.opendota.common.payload.common.Step;
import com.opendota.common.web.BusinessException;
import com.opendota.diag.arbitration.EcuLockRegistry;
import com.opendota.diag.dispatch.DiagDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchCmdServiceTest {

    private static final Operator OPERATOR = new Operator("eng-1", OperatorRole.ENGINEER, "tenant-1", "ticket-1");
    private static final String VIN = "LSVWA234567890123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DiagDispatcher dispatcher;
    private DiagRecordRepository diagRecordRepository;
    private DiagCmdMetrics metrics;
    private EcuLockRegistry ecuLockRegistry;
    private BatchCmdService service;

    @BeforeEach
    void setUp() {
        dispatcher = mock(DiagDispatcher.class);
        diagRecordRepository = mock(DiagRecordRepository.class);
        metrics = mock(DiagCmdMetrics.class);
        ecuLockRegistry = mock(EcuLockRegistry.class);
        service = new BatchCmdService(dispatcher, diagRecordRepository, metrics, ecuLockRegistry);

        when(ecuLockRegistry.tryAcquireAll(any(), any())).thenReturn(List.of());
    }

    // ============================================================
    // macro_security 校验
    // ============================================================

    @Test
    void macroSecurityWithValidParamsShouldSucceed() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("level", "03");
        macroParams.put("algoId", "Algo_Chery_V2");

        Step macroStep = new Step(1, "macro_security", null, 5000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        DiagDispatcher.PreparedBatchCmd prepared = preparedBatch(request);
        when(dispatcher.prepareBatchCmd(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedBatchCmd(prepared);

        String msgId = service.dispatch(request, OPERATOR);
        assertEquals(prepared.envelope().msgId(), msgId);
        verify(metrics).recordMacroDispatch(List.of("macro_security"));
    }

    @Test
    void macroSecurityMissingLevelShouldFail() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("algoId", "Algo_Chery_V2");
        // level 缺失

        Step macroStep = new Step(1, "macro_security", null, 5000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.dispatch(request, OPERATOR));
        assertTrue(ex.getMessage().contains("macroParams.level"));
    }

    @Test
    void macroSecurityMissingAlgoIdShouldFail() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("level", "03");
        // algoId 缺失

        Step macroStep = new Step(1, "macro_security", null, 5000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.dispatch(request, OPERATOR));
        assertTrue(ex.getMessage().contains("macroParams.algoId"));
    }

    @Test
    void macroSecurityWithNullParamsShouldFail() {
        Step macroStep = new Step(1, "macro_security", null, 5000, null);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        try {
            service.dispatch(request, OPERATOR);
            org.junit.jupiter.api.Assertions.fail("Expected BusinessException");
        } catch (BusinessException ex) {
            assertTrue(ex.getMessage().contains("macroParams.level"),
                    "Expected 'macroParams.level' in message but got: " + ex.getMessage());
        }
    }

    // ============================================================
    // macro_routine_wait 校验
    // ============================================================

    @Test
    void macroRoutineWaitWithValidParamsShouldSucceed() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("data", "31010203");
        macroParams.put("maxWaitMs", 15000);

        Step macroStep = new Step(1, "macro_routine_wait", null, 20000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        DiagDispatcher.PreparedBatchCmd prepared = preparedBatch(request);
        when(dispatcher.prepareBatchCmd(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedBatchCmd(prepared);

        String msgId = service.dispatch(request, OPERATOR);
        assertEquals(prepared.envelope().msgId(), msgId);
        verify(metrics).recordMacroDispatch(List.of("macro_routine_wait"));
    }

    @Test
    void macroRoutineWaitMissingDataShouldFail() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("maxWaitMs", 15000);
        // data 缺失

        Step macroStep = new Step(1, "macro_routine_wait", null, 20000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.dispatch(request, OPERATOR));
        assertTrue(ex.getMessage().contains("macroParams.data"));
    }

    @Test
    void macroRoutineWaitMissingMaxWaitMsShouldFail() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("data", "31010203");
        // maxWaitMs 缺失

        Step macroStep = new Step(1, "macro_routine_wait", null, 20000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.dispatch(request, OPERATOR));
        assertTrue(ex.getMessage().contains("macroParams.maxWaitMs"));
    }

    // ============================================================
    // macro_data_transfer 校验
    // ============================================================

    @Test
    void macroDataTransferWithValidParamsShouldSucceed() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("direction", "download");
        macroParams.put("transferSessionId", "xfer-001");
        macroParams.put("fileSha256", "a3f2b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b");
        macroParams.put("fileSize", 131072);

        Step macroStep = new Step(1, "macro_data_transfer", null, 60000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        DiagDispatcher.PreparedBatchCmd prepared = preparedBatch(request);
        when(dispatcher.prepareBatchCmd(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedBatchCmd(prepared);

        String msgId = service.dispatch(request, OPERATOR);
        assertEquals(prepared.envelope().msgId(), msgId);
        verify(metrics).recordMacroDispatch(List.of("macro_data_transfer"));
    }

    @Test
    void macroDataTransferMissingDirectionShouldFail() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("transferSessionId", "xfer-001");
        macroParams.put("fileSha256", "abc123");
        macroParams.put("fileSize", 1024);
        // direction 缺失

        Step macroStep = new Step(1, "macro_data_transfer", null, 60000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(macroStep));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.dispatch(request, OPERATOR));
        assertTrue(ex.getMessage().contains("macroParams.direction"));
    }

    // ============================================================
    // 混合步骤(raw_uds + macro)
    // ============================================================

    @Test
    void mixedStepsWithRawUdsAndMacroShouldSucceed() {
        ObjectNode macroParams = MAPPER.createObjectNode();
        macroParams.put("level", "01");
        macroParams.put("algoId", "Algo_V1");

        Step rawStep = new Step(1, "raw_uds", "22F190", 3000, null);
        Step macroStep = new Step(2, "macro_security", null, 5000, macroParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(rawStep, macroStep));

        DiagDispatcher.PreparedBatchCmd prepared = preparedBatch(request);
        when(dispatcher.prepareBatchCmd(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedBatchCmd(prepared);

        String msgId = service.dispatch(request, OPERATOR);
        assertEquals(prepared.envelope().msgId(), msgId);
        verify(metrics).recordMacroDispatch(List.of("macro_security"));
    }

    @Test
    void multipleMacroTypesInOneBatchShouldTrackAll() {
        ObjectNode secParams = MAPPER.createObjectNode();
        secParams.put("level", "01");
        secParams.put("algoId", "Algo_V1");

        ObjectNode routineParams = MAPPER.createObjectNode();
        routineParams.put("data", "31010203");
        routineParams.put("maxWaitMs", 10000);

        Step secStep = new Step(1, "macro_security", null, 5000, secParams);
        Step routineStep = new Step(2, "macro_routine_wait", null, 15000, routineParams);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(secStep, routineStep));

        DiagDispatcher.PreparedBatchCmd prepared = preparedBatch(request);
        when(dispatcher.prepareBatchCmd(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedBatchCmd(prepared);

        service.dispatch(request, OPERATOR);
        verify(metrics).recordMacroDispatch(List.of("macro_security", "macro_routine_wait"));
    }

    // ============================================================
    // raw_uds 步骤不需要宏参数校验
    // ============================================================

    @Test
    void rawUdsStepWithoutMacroParamsShouldSucceed() {
        Step rawStep = new Step(1, "raw_uds", "22F190", 3000, null);
        BatchCmdController.BatchCmdRequest request = new BatchCmdController.BatchCmdRequest(
                VIN, "VCU", List.of("VCU"), null, "0x7E0", "0x7E8", 1, List.of(rawStep));

        DiagDispatcher.PreparedBatchCmd prepared = preparedBatch(request);
        when(dispatcher.prepareBatchCmd(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(prepared);
        doNothing().when(dispatcher).publishPreparedBatchCmd(prepared);

        String msgId = service.dispatch(request, OPERATOR);
        assertEquals(prepared.envelope().msgId(), msgId);
    }

    // ============================================================
    // extractMacroTypes 工具方法
    // ============================================================

    @Test
    void extractMacroTypesShouldReturnUniqueMacroTypes() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("level", "01");
        params.put("algoId", "Algo_V1");

        Step raw = new Step(1, "raw_uds", "22F190", 3000, null);
        Step sec1 = new Step(2, "macro_security", null, 5000, params);
        Step sec2 = new Step(3, "macro_security", null, 5000, params);
        Step routine = new Step(4, "macro_routine_wait", null, 15000, params);

        List<String> types = BatchCmdService.extractMacroTypes(List.of(raw, sec1, sec2, routine));
        assertEquals(2, types.size());
        assertTrue(types.contains("macro_security"));
        assertTrue(types.contains("macro_routine_wait"));
    }

    @Test
    void extractMacroTypesShouldReturnEmptyForNoMacro() {
        Step raw = new Step(1, "raw_uds", "22F190", 3000, null);
        List<String> types = BatchCmdService.extractMacroTypes(List.of(raw));
        assertTrue(types.isEmpty());
    }

    @Test
    void extractMacroTypesShouldHandleNullSteps() {
        List<String> types = BatchCmdService.extractMacroTypes(null);
        assertTrue(types.isEmpty());
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private DiagDispatcher.PreparedBatchCmd preparedBatch(BatchCmdController.BatchCmdRequest request) {
        BatchDiagPayload payload = new BatchDiagPayload(
                null, request.ecuName(), request.ecuScope(), request.transport(),
                request.txId(), request.rxId(), null, request.strategy(), request.steps());
        String msgId = DiagMessage.newMsgId();
        DiagMessage<BatchDiagPayload> envelope = new DiagMessage<>(
                msgId, System.currentTimeMillis(), VIN, DiagAction.BATCH_CMD, OPERATOR, null, payload);
        return new DiagDispatcher.PreparedBatchCmd(envelope);
    }
}
