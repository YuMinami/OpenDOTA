package com.opendota.task.lifecycle;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 车辆生命周期 Kafka 消费者配置。
 *
 * <p>与分发消费者({@code dispatchKafkaListenerContainerFactory})独立,
 * 使用 BATCH ACK 模式 + 高并发(8 线程),适配大规模车辆上线场景。
 */
@Configuration
public class LifecycleKafkaConfig {

    @Bean
    public ConsumerFactory<String, String> lifecycleConsumerFactory(KafkaProperties kafkaProps) {
        Map<String, Object> configs = new HashMap<>(kafkaProps.buildConsumerProperties());
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> lifecycleKafkaListenerContainerFactory(
            ConsumerFactory<String, String> lifecycleConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(lifecycleConsumerFactory);
        factory.setConcurrency(8);
        return factory;
    }
}
