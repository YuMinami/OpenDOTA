package com.opendota.diag.sse;

import com.opendota.diag.cmd.SseEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterManagerTest {

    @Test
    void shouldDeduplicateEventsPerEmitter() {
        RecordingTransport transport = new RecordingTransport();
        SseEmitterManager manager = new SseEmitterManager(transport);
        SseEmitter emitter = new SseEmitter(0L);
        SseEmitterManager.EmitterSession session = manager.register("LSVWA234567890123", emitter, 10L);

        SseEventRepository.StoredSseEvent event11 = event(11L, Map.of("msgId", "msg-11"));
        SseEventRepository.StoredSseEvent event10 = event(10L, Map.of("msgId", "msg-10"));
        SseEventRepository.StoredSseEvent event12 = event(12L, Map.of("msgId", "msg-12"));

        manager.pushLocal("LSVWA234567890123", event11);
        manager.pushLocal("LSVWA234567890123", event11);
        manager.pushLocal("LSVWA234567890123", event10);
        manager.replay(session, List.of(event11, event12));

        assertThat(transport.events(emitter))
                .extracting(SentFrame::eventId)
                .containsExactly(11L, 12L);
    }

    private static SseEventRepository.StoredSseEvent event(long id, Map<String, Object> payload) {
        return new SseEventRepository.StoredSseEvent(
                id,
                "default-tenant",
                "LSVWA234567890123",
                "diag-result",
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
                    .add(new SentFrame("replay-truncated", null, Map.of("resumeFromId", resumeFromId)));
        }

        List<SentFrame> events(SseEmitter emitter) {
            return sent.getOrDefault(emitter, List.of());
        }
    }
}
