package com.opendota.task.dispatch;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 分发消费者 Kafka 配置。
 *
 * <p>手动 ACK 模式({@code MANUAL_IMMEDIATE}),确保消息在 MQTT publish 成功后才提交 offset,
 * 防止消费端崩溃导致消息丢失。
 */
@Configuration
public class DispatchKafkaConfig {

    @Bean
    public ConsumerFactory<String, String> dispatchConsumerFactory(KafkaProperties kafkaProps) {
        Map<String, Object> configs = new HashMap<>(kafkaProps.buildConsumerProperties());
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dispatchKafkaListenerContainerFactory(
            ConsumerFactory<String, String> dispatchConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dispatchConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
