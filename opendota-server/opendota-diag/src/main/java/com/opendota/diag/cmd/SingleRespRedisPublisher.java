package com.opendota.diag.cmd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.diag.sse.RedisHealthProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单步诊断结果 Redis Pub/Sub 发布器。
 *
 * <p>约定通道: {@code dota:resp:{vin}}。Redis 发送失败只影响实时性,不影响 PG 持久化正确性。
 */
@Component
public class SingleRespRedisPublisher {

    private static final Logger log = LoggerFactory.getLogger(SingleRespRedisPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisHealthProbe redisHealthProbe;

    public SingleRespRedisPublisher(StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    RedisHealthProbe redisHealthProbe) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisHealthProbe = redisHealthProbe;
    }

    public void publishDiagResult(String vin, long sseEventId, Map<String, Object> payloadSummary) {
        String channel = "dota:resp:" + vin;
        Map<String, Object> message = new LinkedHashMap<>(payloadSummary);
        message.put("sseEventId", sseEventId);
        message.put("eventType", "diag-result");

        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, json);
            log.debug("Redis PUB channel={} sseEventId={} msgId={}", channel, sseEventId, payloadSummary.get("msgId"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Redis payload JSON 序列化失败", e);
        } catch (Exception e) {
            redisHealthProbe.markUnhealthy(e);
            log.warn("Redis PUB 失败 channel={} sseEventId={}", channel, sseEventId, e);
        }
    }
}
