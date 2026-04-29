package com.opendota.diag.sse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.diag.cmd.SseEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Pub/Sub → 本机 SSE 连接的分发器。
 */
@Component
public class SseEventDispatcher implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SseEventDispatcher.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final SseEmitterManager emitterManager;
    private final RedisHealthProbe redisHealthProbe;
    private final FallbackSseScanner fallbackSseScanner;

    public SseEventDispatcher(ObjectMapper objectMapper,
                              SseEmitterManager emitterManager,
                              RedisHealthProbe redisHealthProbe,
                              FallbackSseScanner fallbackSseScanner) {
        this.objectMapper = objectMapper;
        this.emitterManager = emitterManager;
        this.redisHealthProbe = redisHealthProbe;
        this.fallbackSseScanner = fallbackSseScanner;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            SseEventRepository.StoredSseEvent event = decode(channel, payload);
            fallbackSseScanner.advanceCursor(event.id());
            emitterManager.pushLocal(event.vin(), event);
        } catch (Exception ex) {
            redisHealthProbe.markUnhealthy(ex);
            log.warn("Redis SSE 消息分发失败 channel={}", channel, ex);
        }
    }

    private SseEventRepository.StoredSseEvent decode(String channel, String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        long eventId = requiredLong(root, "sseEventId");
        String eventType = nullableText(root.get("eventType"));
        if (eventType == null && channel.startsWith("dota:resp:")) {
            eventType = "diag-result";
        }

        Map<String, Object> summary = new LinkedHashMap<>(objectMapper.convertValue(root, MAP_TYPE));
        summary.remove("sseEventId");
        summary.remove("eventType");

        String vin = nullableText(root.get("vin"));
        if (vin == null) {
            vin = extractVin(channel);
        }
        if (vin == null || eventType == null) {
            throw new IllegalArgumentException("Redis SSE 消息缺少 vin/eventType: channel=" + channel);
        }

        return new SseEventRepository.StoredSseEvent(
                eventId,
                null,
                vin,
                eventType,
                "redis_pubsub",
                null,
                summary,
                Instant.now());
    }

    private static long requiredLong(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Redis SSE 消息缺少数值字段: " + fieldName);
        }
        return value.longValue();
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String extractVin(String channel) {
        int index = channel.lastIndexOf(':');
        if (index < 0 || index == channel.length() - 1) {
            return null;
        }
        return channel.substring(index + 1);
    }
}

@Configuration
class SseRedisListenerConfiguration {

    @Bean
    RedisMessageListenerContainer sseRedisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                  SseEventDispatcher dispatcher,
                                                                  RedisHealthProbe redisHealthProbe) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setErrorHandler(redisHealthProbe::markUnhealthy);
        container.addMessageListener(dispatcher, new PatternTopic("dota:resp:*"));
        return container;
    }
}
