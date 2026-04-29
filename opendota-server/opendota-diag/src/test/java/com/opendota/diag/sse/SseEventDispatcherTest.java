package com.opendota.diag.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.diag.cmd.SseEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SseEventDispatcherTest {

    private SseEmitterManager emitterManager;
    private RedisHealthProbe redisHealthProbe;
    private FallbackSseScanner fallbackSseScanner;
    private SseEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        emitterManager = mock(SseEmitterManager.class);
        redisHealthProbe = mock(RedisHealthProbe.class);
        fallbackSseScanner = mock(FallbackSseScanner.class);
        dispatcher = new SseEventDispatcher(new ObjectMapper(), emitterManager, redisHealthProbe, fallbackSseScanner);
    }

    @Test
    void shouldDispatchRedisPayloadToLocalEmitters() {
        Message message = new DefaultMessage(
                "dota:resp:LSVWA234567890123".getBytes(),
                """
                {"sseEventId":88,"eventType":"diag-result","vin":"LSVWA234567890123","msgId":"msg-88","summaryText":"ok"}
                """.getBytes());

        dispatcher.onMessage(message, null);

        verify(fallbackSseScanner).advanceCursor(88L);
        verify(emitterManager).pushLocal(eq("LSVWA234567890123"), argThat(event ->
                event.id() == 88L
                        && "diag-result".equals(event.eventType())
                        && "msg-88".equals(event.payloadSummary().get("msgId"))
                        && !event.payloadSummary().containsKey("sseEventId")
                        && !event.payloadSummary().containsKey("eventType")));
        verify(redisHealthProbe, never()).markUnhealthy(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldInferDiagResultAndVinFromChannelWhenPayloadOmitsThem() {
        Message message = new DefaultMessage(
                "dota:resp:LSVWA234567890123".getBytes(),
                """
                {"sseEventId":89,"msgId":"msg-89"}
                """.getBytes());

        dispatcher.onMessage(message, null);

        verify(emitterManager).pushLocal(eq("LSVWA234567890123"), argThat(event ->
                event.id() == 89L
                        && "diag-result".equals(event.eventType())
                        && "msg-89".equals(event.payloadSummary().get("msgId"))));
    }

    @Test
    void shouldMarkRedisUnhealthyWhenPayloadInvalid() {
        Message message = new DefaultMessage(
                "dota:resp:LSVWA234567890123".getBytes(),
                """
                {"vin":"LSVWA234567890123"}
                """.getBytes());

        dispatcher.onMessage(message, null);

        verify(redisHealthProbe).markUnhealthy(org.mockito.ArgumentMatchers.any());
        verify(emitterManager, never()).pushLocal(eq("LSVWA234567890123"), org.mockito.ArgumentMatchers.any());
    }
}
