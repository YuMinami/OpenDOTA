package com.opendota.diag.sse;

import com.opendota.diag.cmd.SseEventRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 不可用时的本机 SSE 兜底扫描器。
 */
@Component
public class FallbackSseScanner {

    private static final Logger log = LoggerFactory.getLogger(FallbackSseScanner.class);
    private static final int SCAN_BATCH_SIZE = 500;

    private final SseEventRepository sseEventRepository;
    private final SseEmitterManager emitterManager;
    private final RedisHealthProbe redisHealthProbe;
    private final AtomicLong lastScannedId = new AtomicLong(0L);

    public FallbackSseScanner(SseEventRepository sseEventRepository,
                              SseEmitterManager emitterManager,
                              RedisHealthProbe redisHealthProbe) {
        this.sseEventRepository = sseEventRepository;
        this.emitterManager = emitterManager;
        this.redisHealthProbe = redisHealthProbe;
    }

    @PostConstruct
    void initializeCursor() {
        lastScannedId.set(sseEventRepository.findMaxId());
    }

    public void advanceCursor(long eventId) {
        lastScannedId.accumulateAndGet(eventId, Math::max);
    }

    @Scheduled(fixedDelay = 500)
    public void scan() {
        if (redisHealthProbe.isRedisHealthy()) {
            return;
        }

        try {
            List<SseEventRepository.StoredSseEvent> rows =
                    sseEventRepository.findAfterId(lastScannedId.get(), SCAN_BATCH_SIZE);
            for (SseEventRepository.StoredSseEvent row : rows) {
                emitterManager.pushLocal(row.vin(), row);
                advanceCursor(row.id());
            }
        } catch (Exception ex) {
            log.warn("fallback sse 扫描失败 lastScannedId={}", lastScannedId.get(), ex);
        }
    }
}
