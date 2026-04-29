package com.opendota.diag.cmd;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.single.SingleRespPayload;
import com.opendota.diag.channel.ChannelManager;
import com.opendota.odx.service.OdxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleRespHandlerTest {

    private OdxService odxService;
    private ChannelManager channelManager;
    private DiagRecordRepository diagRecordRepository;
    private SseEventRepository sseEventRepository;
    private SingleRespRedisPublisher redisPublisher;
    private DiagCmdMetrics metrics;
    private SingleRespHandler handler;

    @BeforeEach
    void setUp() {
        odxService = mock(OdxService.class);
        channelManager = mock(ChannelManager.class);
        diagRecordRepository = mock(DiagRecordRepository.class);
        sseEventRepository = mock(SseEventRepository.class);
        redisPublisher = mock(SingleRespRedisPublisher.class);
        metrics = mock(DiagCmdMetrics.class);
        handler = new SingleRespHandler(
                odxService, channelManager, diagRecordRepository, sseEventRepository, redisPublisher, metrics);
    }

    @Test
    void handleShouldUpdateDiagRecordAndPublishSseEvent() {
        when(diagRecordRepository.findByMsgId("550e8400-e29b-41d4-a716-446655440000"))
                .thenReturn(Optional.of(new DiagRecordRepository.DiagRecordContext(
                        7L, "550e8400-e29b-41d4-a716-446655440000", "chery-hq",
                        "LSVWA234567890123", "VCU", "22F112",
                        "eng-12345", "engineer", "DIAG-1", "trace-1")));
        when(odxService.translateSingleResponse("LSVWA234567890123", "VCU", "22F112", "62F1123B"))
                .thenReturn(Map.of(
                        "translationType", "positive",
                        "summaryText", "读取电池温度: 电池温度探针 A=19°C"));
        when(diagRecordRepository.upsertSingleResponse(any(DiagRecordRepository.CompletedSingleCmd.class)))
                .thenReturn(7L);
        when(sseEventRepository.insertDiagResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(7L), any(Map.class)))
                .thenReturn(88L);

        handler.handle("dota/v1/resp/single/LSVWA234567890123", new DiagMessage<>(
                "resp-msg-1",
                System.currentTimeMillis(),
                "LSVWA234567890123",
                DiagAction.SINGLE_RESP,
                new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-1"),
                null,
                new SingleRespPayload("550e8400-e29b-41d4-a716-446655440000", "ch-1", 0, "", "62F1123B", 128)));

        verify(odxService).translateSingleResponse("LSVWA234567890123", "VCU", "22F112", "62F1123B");
        verify(diagRecordRepository).upsertSingleResponse(any(DiagRecordRepository.CompletedSingleCmd.class));
        verify(sseEventRepository).insertDiagResult(eq("chery-hq"), eq("LSVWA234567890123"), eq(7L), any(Map.class));
        verify(redisPublisher).publishDiagResult(eq("LSVWA234567890123"), eq(88L), any(Map.class));
        verify(channelManager).markIdle("ch-1");
    }
}
