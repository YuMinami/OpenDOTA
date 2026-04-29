package com.opendota.diag.cmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.batch.BatchStepResult;
import com.opendota.common.payload.script.ScriptEcuResult;
import com.opendota.common.payload.script.ScriptRespPayload;
import com.opendota.odx.service.OdxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScriptRespHandlerTest {

    private OdxService odxService;
    private DiagRecordRepository diagRecordRepository;
    private SseEventRepository sseEventRepository;
    private SingleRespRedisPublisher redisPublisher;
    private DiagCmdMetrics metrics;
    private ScriptRespHandler handler;

    @BeforeEach
    void setUp() {
        odxService = mock(OdxService.class);
        diagRecordRepository = mock(DiagRecordRepository.class);
        sseEventRepository = mock(SseEventRepository.class);
        redisPublisher = mock(SingleRespRedisPublisher.class);
        metrics = mock(DiagCmdMetrics.class);
        handler = new ScriptRespHandler(
                odxService, diagRecordRepository, sseEventRepository, redisPublisher, metrics, new ObjectMapper());
    }

    @Test
    void handleShouldUpdateDiagRecordAndPublishSseEvent() {
        when(diagRecordRepository.findByMsgId("550e8400-e29b-41d4-a716-446655440099"))
                .thenReturn(Optional.of(new DiagRecordRepository.DiagRecordContext(
                        10L, "550e8400-e29b-41d4-a716-446655440099", "chery-hq",
                        "LSVWA234567890123", "BMS,VCU", "{}",
                        "eng-12345", "engineer", "DIAG-1", "trace-1")));
        when(diagRecordRepository.upsertScriptResponse(any(DiagRecordRepository.CompletedScriptCmd.class)))
                .thenReturn(10L);
        when(sseEventRepository.insertScriptResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(10L), any(Map.class)))
                .thenReturn(99L);

        ScriptEcuResult bmsResult = new ScriptEcuResult("BMS", 0, 1200L, List.of(
                new BatchStepResult(1, 0, null, "62F19001", null)));
        ScriptEcuResult vcuResult = new ScriptEcuResult("VCU", 0, 800L, List.of(
                new BatchStepResult(1, 0, null, "62F19002", null)));
        ScriptRespPayload payload = new ScriptRespPayload("script-001", 0, 2000L, List.of(bmsResult, vcuResult));

        handler.handle("dota/v1/resp/script/LSVWA234567890123", new DiagMessage<>(
                "resp-script-1",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.SCRIPT_RESP,
                new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1"),
                null,
                payload));

        verify(diagRecordRepository).upsertScriptResponse(any(DiagRecordRepository.CompletedScriptCmd.class));
        verify(sseEventRepository).insertScriptResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(10L), any(Map.class));
        verify(redisPublisher).publishScriptResult(eq("LSVWA234567890123"), eq(99L), any(Map.class));
        verify(metrics).recordScriptResponse("resp-script-1", true);
    }

    @Test
    void handleShouldSkipNonScriptRespPayload() {
        // 发送一个非 ScriptRespPayload 的 envelope,应该被忽略
        handler.handle("dota/v1/resp/script/LSVWA234567890123", new DiagMessage<>(
                "resp-script-2",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.SCRIPT_RESP,
                null,
                null,
                "not-a-script-payload"));

        // 不应有任何交互
        verify(diagRecordRepository, org.mockito.Mockito.never()).findByMsgId(any());
    }
}
