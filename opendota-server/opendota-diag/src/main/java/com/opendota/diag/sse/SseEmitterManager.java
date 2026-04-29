package com.opendota.diag.sse;

import com.opendota.diag.cmd.SseEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本机 SSE 连接管理器。
 *
 * <p>职责:
 * <ul>
 *   <li>按 VIN 维护本机活跃 {@link SseEmitter}</li>
 *   <li>统一做事件发送与去重,避免 Redis/回放/降级扫描产生重复推送</li>
 *   <li>在连接断开时及时摘除,防止内存泄漏</li>
 * </ul>
 */
@Component
public class SseEmitterManager {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterManager.class);

    private final ConcurrentMap<String, CopyOnWriteArrayList<EmitterSession>> emittersByVin = new ConcurrentHashMap<>();
    private final EmitterTransport transport;

    public SseEmitterManager() {
        this(new DefaultEmitterTransport());
    }

    SseEmitterManager(EmitterTransport transport) {
        this.transport = transport;
    }

    public EmitterSession register(String vin, SseEmitter emitter, long lastSeenEventId) {
        EmitterSession session = new EmitterSession(vin, emitter, lastSeenEventId);
        emittersByVin.computeIfAbsent(vin, ignored -> new CopyOnWriteArrayList<>()).add(session);
        return session;
    }

    public void remove(EmitterSession session) {
        if (session == null) {
            return;
        }
        CopyOnWriteArrayList<EmitterSession> sessions = emittersByVin.get(session.vin());
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            emittersByVin.remove(session.vin(), sessions);
        }
    }

    public void replay(EmitterSession session, List<SseEventRepository.StoredSseEvent> events) {
        if (session == null || events == null || events.isEmpty()) {
            return;
        }
        for (SseEventRepository.StoredSseEvent event : events) {
            sendIfNecessary(session, event);
        }
    }

    public void pushLocal(String vin, SseEventRepository.StoredSseEvent event) {
        if (vin == null || event == null) {
            return;
        }
        CopyOnWriteArrayList<EmitterSession> sessions = emittersByVin.get(vin);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (EmitterSession session : sessions) {
            sendIfNecessary(session, event);
        }
    }

    public void sendReplayTruncated(EmitterSession session, long resumeFromId) {
        if (session == null) {
            return;
        }
        try {
            transport.sendReplayTruncated(session.emitter(), resumeFromId);
        } catch (IOException | IllegalStateException ex) {
            dropSession(session, ex);
        }
    }

    private void sendIfNecessary(EmitterSession session, SseEventRepository.StoredSseEvent event) {
        synchronized (session.monitor()) {
            if (event.id() <= session.lastSeenEventId().get()) {
                return;
            }
            try {
                transport.sendEvent(session.emitter(), event);
                session.lastSeenEventId().set(event.id());
            } catch (IOException | IllegalStateException ex) {
                dropSession(session, ex);
            }
        }
    }

    private void dropSession(EmitterSession session, Exception ex) {
        log.debug("SSE emitter 发送失败,移除本地连接 vin={}", session.vin(), ex);
        remove(session);
        try {
            session.emitter().completeWithError(ex);
        } catch (Exception ignored) {
            session.emitter().complete();
        }
    }

    public static final class EmitterSession {

        private final String vin;
        private final SseEmitter emitter;
        private final AtomicLong lastSeenEventId;
        private final Object monitor = new Object();

        private EmitterSession(String vin, SseEmitter emitter, long lastSeenEventId) {
            this.vin = vin;
            this.emitter = emitter;
            this.lastSeenEventId = new AtomicLong(Math.max(lastSeenEventId, 0L));
        }

        public String vin() {
            return vin;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        AtomicLong lastSeenEventId() {
            return lastSeenEventId;
        }

        Object monitor() {
            return monitor;
        }
    }

    interface EmitterTransport {

        void sendEvent(SseEmitter emitter, SseEventRepository.StoredSseEvent event) throws IOException;

        void sendReplayTruncated(SseEmitter emitter, long resumeFromId) throws IOException;
    }

    static final class DefaultEmitterTransport implements EmitterTransport {

        @Override
        public void sendEvent(SseEmitter emitter, SseEventRepository.StoredSseEvent event) throws IOException {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.id()))
                    .name(event.eventType())
                    .data(event.payloadSummary()));
        }

        @Override
        public void sendReplayTruncated(SseEmitter emitter, long resumeFromId) throws IOException {
            emitter.send(SseEmitter.event()
                    .name("replay-truncated")
                    .data(Map.of(
                            "suggestFullReload", true,
                            "resumeFromId", resumeFromId)));
        }
    }
}
