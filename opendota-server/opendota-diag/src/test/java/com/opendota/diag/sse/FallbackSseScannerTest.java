package com.opendota.diag.sse;

import com.opendota.diag.cmd.SseEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FallbackSseScannerTest {

    private SseEventRepository sseEventRepository;
    private SseEmitterManager emitterManager;
    private RedisHealthProbe redisHealthProbe;
    private FallbackSseScanner scanner;

    @BeforeEach
    void setUp() {
        sseEventRepository = mock(SseEventRepository.class);
        emitterManager = mock(SseEmitterManager.class);
        redisHealthProbe = mock(RedisHealthProbe.class);
        scanner = new FallbackSseScanner(sseEventRepository, emitterManager, redisHealthProbe);
    }

    @Test
    void shouldScanNewRowsWhenRedisIsUnhealthy() {
        SseEventRepository.StoredSseEvent event21 = event(21L, "LSVWA234567890123");
        SseEventRepository.StoredSseEvent event22 = event(22L, "LSVWB987654321098");

        when(sseEventRepository.findMaxId()).thenReturn(20L);
        when(redisHealthProbe.isRedisHealthy()).thenReturn(false);
        when(sseEventRepository.findAfterId(20L, 500)).thenReturn(List.of(event21, event22));
        when(sseEventRepository.findAfterId(22L, 500)).thenReturn(List.of());

        scanner.initializeCursor();
        scanner.scan();
        scanner.scan();

        verify(emitterManager).pushLocal("LSVWA234567890123", event21);
        verify(emitterManager).pushLocal("LSVWB987654321098", event22);
        verify(sseEventRepository).findAfterId(22L, 500);
    }

    private static SseEventRepository.StoredSseEvent event(long id, String vin) {
        return new SseEventRepository.StoredSseEvent(
                id,
                "default-tenant",
                vin,
                "diag-result",
                "diag_record",
                id,
                Map.of("msgId", "msg-" + id),
                Instant.now());
    }
}
