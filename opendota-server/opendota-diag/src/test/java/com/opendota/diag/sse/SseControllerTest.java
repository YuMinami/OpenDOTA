package com.opendota.diag.sse;

import com.opendota.diag.cmd.SseEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SseControllerTest {

    private SseEventRepository sseEventRepository;
    private RecordingTransport transport;
    private SseController controller;

    @BeforeEach
    void setUp() {
        sseEventRepository = mock(SseEventRepository.class);
        transport = new RecordingTransport();
        controller = new SseController(sseEventRepository, new SseEmitterManager(transport));
    }

    @Test
    void shouldPreferLastEventIdHeaderOverQueryParameter() {
        when(sseEventRepository.findReplayEvents("LSVWA234567890123", 41L, SseController.REPLAY_LIMIT))
                .thenReturn(List.of(
                        event(42L, "diag-result", Map.of("msgId", "msg-42")),
                        event(43L, "diag-result", Map.of("msgId", "msg-43"))));

        SseEmitter emitter = controller.subscribe("LSVWA234567890123", "41", "7");

        verify(sseEventRepository).findReplayEvents("LSVWA234567890123", 41L, SseController.REPLAY_LIMIT);
        assertThat(transport.events(emitter))
                .extracting(SentFrame::eventType, SentFrame::eventId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("diag-result", 42L),
                        org.assertj.core.groups.Tuple.tuple("diag-result", 43L));
    }

    @Test
    void shouldReplayFromQueryParameterWhenHeaderMissing() {
        when(sseEventRepository.findReplayEvents("LSVWA234567890123", 7L, SseController.REPLAY_LIMIT))
                .thenReturn(List.of(event(8L, "channel-event", Map.of("channelId", "ch-1"))));

        SseEmitter emitter = controller.subscribe("LSVWA234567890123", null, "7");

        verify(sseEventRepository).findReplayEvents("LSVWA234567890123", 7L, SseController.REPLAY_LIMIT);
        assertThat(transport.events(emitter))
                .singleElement()
                .satisfies(frame -> {
                    assertThat(frame.eventType()).isEqualTo("channel-event");
                    assertThat(frame.eventId()).isEqualTo(8L);
                    assertThat(frame.payload()).containsEntry("channelId", "ch-1");
                });
    }

    @Test
    void shouldSendReplayTruncatedMarkerWhenReplayHitsLimit() {
        List<SseEventRepository.StoredSseEvent> rows = new ArrayList<>();
        for (long id = 2; id <= 1001; id++) {
            rows.add(event(id, "diag-result", Map.of("msgId", "msg-" + id)));
        }
        when(sseEventRepository.findReplayEvents("LSVWA234567890123", 1L, SseController.REPLAY_LIMIT))
                .thenReturn(rows);

        SseEmitter emitter = controller.subscribe("LSVWA234567890123", "1", null);

        assertThat(transport.events(emitter)).hasSize(1001);
        SentFrame truncated = transport.events(emitter).getLast();
        assertThat(truncated.eventType()).isEqualTo("replay-truncated");
        assertThat(truncated.eventId()).isNull();
        assertThat(truncated.payload()).containsEntry("suggestFullReload", true);
        assertThat(truncated.payload()).containsEntry("resumeFromId", 1001L);
    }

    private static SseEventRepository.StoredSseEvent event(long id, String eventType, Map<String, Object> payload) {
        return new SseEventRepository.StoredSseEvent(
                id,
                "default-tenant",
                "LSVWA234567890123",
                eventType,
                "diag_record",
                id,
                payload,
                Instant.now());
    }

    private record SentFrame(String eventType, Long eventId, Map<String, Object> payload) {
    }

    private static final class RecordingTransport implements SseEmitterManager.EmitterTransport {

        private final IdentityHashMap<SseEmitter, List<SentFrame>> sent = new IdentityHashMap<>();

        @Override
        public void sendEvent(SseEmitter emitter, SseEventRepository.StoredSseEvent event) throws IOException {
            sent.computeIfAbsent(emitter, ignored -> new ArrayList<>())
                    .add(new SentFrame(event.eventType(), event.id(), event.payloadSummary()));
        }

        @Override
        public void sendReplayTruncated(SseEmitter emitter, long resumeFromId) throws IOException {
            sent.computeIfAbsent(emitter, ignored -> new ArrayList<>())
                    .add(new SentFrame("replay-truncated", null, Map.of(
                            "suggestFullReload", true,
                            "resumeFromId", resumeFromId)));
        }

        List<SentFrame> events(SseEmitter emitter) {
            return sent.getOrDefault(emitter, List.of());
        }
    }
}
