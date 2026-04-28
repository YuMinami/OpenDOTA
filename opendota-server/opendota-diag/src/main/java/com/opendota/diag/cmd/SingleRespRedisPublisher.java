package com.opendota.diag.cmd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public SingleRespRedisPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishDiagResult(String vin, long sseEventId, Map<String, Object> payloadSummary) {
        String channel = "dota:resp:" + vin;
        Map<String, Object> message = new LinkedHashMap<>(payloadSummary);
        message.put("sseEventId", sseEventId);

        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, json);
            log.debug("Redis PUB channel={} sseEventId={} msgId={}", channel, sseEventId, payloadSummary.get("msgId"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Redis payload JSON 序列化失败", e);
        } catch (Exception e) {
            log.warn("Redis PUB 失败 channel={} sseEventId={}", channel, sseEventId, e);
        }
    }
}
