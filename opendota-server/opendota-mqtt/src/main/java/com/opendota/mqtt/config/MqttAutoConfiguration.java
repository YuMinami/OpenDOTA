package com.opendota.mqtt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.mqtt.client.CloudMqttClientFactory;
import com.opendota.mqtt.client.MqttPublisher;
import com.opendota.mqtt.client.MqttSubscriber;
import com.opendota.mqtt.codec.EnvelopeCodec;
import com.opendota.mqtt.listener.V2cListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * opendota-mqtt 自动装配。
 *
 * <p>总开关 {@code opendota.mqtt.enabled=true}(默认启用)。测试里若不想连 broker,可显式置 false。
 *
 * <p>装配顺序:
 * <ol>
 *   <li>{@link EnvelopeCodec} —— 只依赖 {@link ObjectMapper}</li>
 *   <li>{@link MqttClient} —— 连 broker,抛异常会让应用启动失败(fail-fast)</li>
 *   <li>{@link MqttPublisher} —— 无状态发布器</li>
 *   <li>{@link MqttSubscriber} —— 收集所有 {@link V2cListener} 构造,订阅在 Lifecycle 的 @PostConstruct 里完成</li>
 *   <li>{@link MqttLifecycle} —— 管 start / stop,与 Spring 容器生命周期同步</li>
 * </ol>
 */
@AutoConfiguration
@EnableConfigurationProperties(MqttProperties.class)
@ConditionalOnProperty(prefix = "opendota.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MqttAutoConfiguration.class);

    @Bean
    public EnvelopeCodec envelopeCodec(ObjectProvider<ObjectMapper> mapperProvider) {
        return new EnvelopeCodec(mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * 云端 MQTT 客户端。destroyMethod 留空,生命周期由 {@link MqttLifecycle} 统一管,
     * 避免 Spring 默认调 {@code close()} 时客户端还没 disconnect 抛异常。
     */
    @Bean(destroyMethod = "")
    public MqttClient cloudMqttClient(MqttProperties props) throws MqttException {
        log.info("🔌 连接云端 MQTT broker: {}", props.getBrokerUrl());
        return CloudMqttClientFactory.build(props);
    }

    @Bean
    public MqttPublisher mqttPublisher(MqttClient client, EnvelopeCodec codec, MqttProperties props) {
        return new MqttPublisher(client, codec, props);
    }

    @Bean
    public MqttSubscriber mqttSubscriber(MqttClient client, EnvelopeCodec codec, MqttProperties props,
                                          ObjectProvider<V2cListener> listeners) {
        return new MqttSubscriber(client, codec, props, listeners.stream().toList());
    }

    @Bean
    public MqttLifecycle mqttLifecycle(MqttClient client, MqttSubscriber subscriber) {
        return new MqttLifecycle(client, subscriber);
    }

    /**
     * 内部 Lifecycle bean:
     *   start() —— PostConstruct,订阅 V2C topics
     *   stop()  —— PreDestroy,顺序 disconnect → close
     */
    public static class MqttLifecycle {

        private static final Logger log = LoggerFactory.getLogger(MqttLifecycle.class);
        private final MqttClient client;
        private final MqttSubscriber subscriber;

        public MqttLifecycle(MqttClient client, MqttSubscriber subscriber) {
            this.client = client;
            this.subscriber = subscriber;
        }

        @PostConstruct
        public void start() throws MqttException {
            subscriber.start();
        }

        @PreDestroy
        public void stop() {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
            } catch (MqttException e) {
                log.warn("cloud MQTT disconnect 失败", e);
            }
            try {
                client.close();
            } catch (MqttException e) {
                log.warn("cloud MQTT close 失败", e);
            }
        }
    }
}
