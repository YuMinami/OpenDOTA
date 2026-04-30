package com.opendota.diag.cmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.batch.BatchRespPayload;
import com.opendota.common.payload.batch.BatchStepResult;
import com.opendota.odx.service.OdxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchRespHandlerTest {

    private OdxService odxService;
    private DiagRecordRepository diagRecordRepository;
    private SseEventRepository sseEventRepository;
    private SingleRespRedisPublisher redisPublisher;
    private DiagCmdMetrics metrics;
    private BatchRespHandler handler;

    @BeforeEach
    void setUp() {
        odxService = mock(OdxService.class);
        diagRecordRepository = mock(DiagRecordRepository.class);
        sseEventRepository = mock(SseEventRepository.class);
        redisPublisher = mock(SingleRespRedisPublisher.class);
        metrics = mock(DiagCmdMetrics.class);
        handler = new BatchRespHandler(
                odxService, diagRecordRepository, sseEventRepository, redisPublisher, metrics, new ObjectMapper());
    }

    @Test
    void supportsShouldReturnTrueForBatchResp() {
        assertThat(handler.supports(DiagAction.BATCH_RESP)).isTrue();
    }

    @Test
    void supportsShouldReturnFalseForOtherActions() {
        assertThat(handler.supports(DiagAction.SINGLE_RESP)).isFalse();
        assertThat(handler.supports(DiagAction.SCRIPT_RESP)).isFalse();
        assertThat(handler.supports(DiagAction.CHANNEL_EVENT)).isFalse();
    }

    @Test
    void handleShouldUpdateDiagRecordAndPublishSseEvent() {
        when(diagRecordRepository.findByMsgId("batch-msg-001"))
                .thenReturn(Optional.of(new DiagRecordRepository.DiagRecordContext(
                        10L, "batch-msg-001", "chery-hq",
                        "LSVWA234567890123", "VCU", "{\"steps\":[{\"seqId\":1,\"type\":\"raw_uds\",\"data\":\"22F190\"},{\"seqId\":2,\"type\":\"raw_uds\",\"data\":\"22F191\"}]}",
                        "eng-12345", "engineer", "DIAG-1", "trace-1")));
        when(diagRecordRepository.upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class)))
                .thenReturn(10L);
        when(sseEventRepository.insertBatchResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(10L), any(Map.class)))
                .thenReturn(99L);

        BatchStepResult step1 = new BatchStepResult(1, 0, null, "62F19001", null);
        BatchStepResult step2 = new BatchStepResult(2, 0, null, "62F19101", null);
        BatchRespPayload payload = new BatchRespPayload("task-001", 0, 1500L, List.of(step1, step2));

        handler.handle("dota/v1/resp/batch/LSVWA234567890123", new DiagMessage<>(
                "batch-msg-001",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.BATCH_RESP,
                new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1"),
                null,
                payload));

        verify(diagRecordRepository).upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class));
        verify(sseEventRepository).insertBatchResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(10L), any(Map.class));
        verify(redisPublisher).publishBatchResult(eq("LSVWA234567890123"), eq(99L), any(Map.class));
        verify(metrics).recordBatchResponse("batch-msg-001", true);
    }

    @Test
    void handleShouldReportFailureWhenOverallStatusNonZero() {
        when(diagRecordRepository.findByMsgId("batch-msg-002"))
                .thenReturn(Optional.of(new DiagRecordRepository.DiagRecordContext(
                        10L, "batch-msg-002", "chery-hq",
                        "LSVWA234567890123", "VCU", "{\"steps\":[{\"seqId\":1,\"type\":\"raw_uds\",\"data\":\"22F190\"}]}",
                        "eng-12345", "engineer", "DIAG-1", "trace-1")));
        when(diagRecordRepository.upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class)))
                .thenReturn(11L);
        when(sseEventRepository.insertBatchResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(11L), any(Map.class)))
                .thenReturn(100L);

        BatchStepResult failedStep = new BatchStepResult(1, 3, "NRC_0x13", "7F2213", "incorrectMessageLengthOrInvalidFormat");
        BatchRespPayload payload = new BatchRespPayload("task-002", 3, 200L, List.of(failedStep));

        handler.handle("dota/v1/resp/batch/LSVWA234567890123", new DiagMessage<>(
                "batch-msg-002",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.BATCH_RESP,
                new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1"),
                null,
                payload));

        verify(diagRecordRepository).upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class));
        verify(metrics).recordBatchResponse("batch-msg-002", false);
    }

    @Test
    void handleShouldSkipNonBatchRespPayload() {
        handler.handle("dota/v1/resp/batch/LSVWA234567890123", new DiagMessage<>(
                "batch-msg-003",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.BATCH_RESP,
                null,
                null,
                "not-a-batch-payload"));

        verify(diagRecordRepository, never()).findByMsgId(any());
        verify(diagRecordRepository, never()).upsertBatchResponse(any());
        verify(sseEventRepository, never()).insertBatchResult(any(), any(), anyLong(), any());
    }

    @Test
    void handleShouldFallbackWhenNoPendingRecord() {
        when(diagRecordRepository.findByMsgId("batch-msg-004"))
                .thenReturn(Optional.empty());
        when(diagRecordRepository.upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class)))
                .thenReturn(12L);
        when(sseEventRepository.insertBatchResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(12L), any(Map.class)))
                .thenReturn(101L);

        BatchStepResult step = new BatchStepResult(1, 0, null, "62F19001", null);
        BatchRespPayload payload = new BatchRespPayload("task-004", 0, 500L, List.of(step));

        handler.handle("dota/v1/resp/batch/LSVWA234567890123", new DiagMessage<>(
                "batch-msg-004",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.BATCH_RESP,
                new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1"),
                null,
                payload));

        verify(diagRecordRepository).upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class));
        verify(sseEventRepository).insertBatchResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(12L), any(Map.class));
        verify(redisPublisher).publishBatchResult(eq("LSVWA234567890123"), eq(101L), any(Map.class));
    }

    @Test
    void handleShouldTolerateNullResults() {
        when(diagRecordRepository.findByMsgId("batch-msg-005"))
                .thenReturn(Optional.of(new DiagRecordRepository.DiagRecordContext(
                        10L, "batch-msg-005", "chery-hq",
                        "LSVWA234567890123", "VCU", null,
                        "eng-12345", "engineer", "DIAG-1", "trace-1")));
        when(diagRecordRepository.upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class)))
                .thenReturn(13L);
        when(sseEventRepository.insertBatchResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(13L), any(Map.class)))
                .thenReturn(102L);

        BatchRespPayload payload = new BatchRespPayload("task-005", 0, 100L, null);

        handler.handle("dota/v1/resp/batch/LSVWA234567890123", new DiagMessage<>(
                "batch-msg-005",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.BATCH_RESP,
                new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1"),
                null,
                payload));

        verify(diagRecordRepository).upsertBatchResponse(any(DiagRecordRepository.CompletedBatchCmd.class));
        verify(sseEventRepository).insertBatchResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(13L), any(Map.class));
        verify(redisPublisher).publishBatchResult(eq("LSVWA234567890123"), eq(102L), any(Map.class));
    }
}
