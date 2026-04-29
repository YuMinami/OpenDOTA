package com.opendota.diag.sse;

import com.opendota.diag.cmd.SseEventRepository;
import com.opendota.diag.web.ApiError;
import com.opendota.diag.web.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * SSE 订阅端点(REST §8)。
 */
@RestController
@RequestMapping("/api/sse")
public class SseController {

    static final int REPLAY_LIMIT = 1000;

    private final SseEventRepository sseEventRepository;
    private final SseEmitterManager emitterManager;

    public SseController(SseEventRepository sseEventRepository, SseEmitterManager emitterManager) {
        this.sseEventRepository = sseEventRepository;
        this.emitterManager = emitterManager;
    }

    @GetMapping(value = "/subscribe/{vin}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String vin,
                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
                                @RequestParam(value = "lastEventId", required = false) String lastEventIdQuery) {
        validateVin(vin);

        long lastEventId = resolveLastEventId(lastEventIdHeader, lastEventIdQuery);
        SseEmitter emitter = new SseEmitter(0L);
        SseEmitterManager.EmitterSession session = emitterManager.register(vin, emitter, lastEventId);
        emitter.onCompletion(() -> emitterManager.remove(session));
        emitter.onTimeout(() -> emitterManager.remove(session));
        emitter.onError(ex -> emitterManager.remove(session));

        if (lastEventId > 0) {
            List<SseEventRepository.StoredSseEvent> missed = sseEventRepository.findReplayEvents(vin, lastEventId, REPLAY_LIMIT);
            emitterManager.replay(session, missed);
            if (missed.size() == REPLAY_LIMIT) {
                emitterManager.sendReplayTruncated(session, missed.get(missed.size() - 1).id());
            }
        }
        return emitter;
    }

    private static void validateVin(String vin) {
        if (vin == null || vin.length() != 17) {
            throw new BusinessException(ApiError.E40001, "vin 必须为 17 位");
        }
    }

    private static long resolveLastEventId(String headerValue, String queryValue) {
        String candidate = hasText(headerValue) ? headerValue : queryValue;
        if (!hasText(candidate)) {
            return 0L;
        }
        try {
            long lastEventId = Long.parseLong(candidate.trim());
            if (lastEventId < 0) {
                throw new BusinessException(ApiError.E40002, "lastEventId 不能为负数");
            }
            return lastEventId;
        } catch (NumberFormatException ex) {
            throw new BusinessException(ApiError.E40002, "lastEventId 必须为数字", ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
