package com.opendota.mqtt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.EnvelopeReader;
import com.opendota.common.envelope.EnvelopeWriter;
import com.opendota.mqtt.client.CloudMqttClientFactory;
import com.opendota.mqtt.codec.EnvelopeCodec;
import com.opendota.mqtt.publisher.MqttPublishMetrics;
import com.opendota.mqtt.publisher.MqttPublisher;
import com.opendota.mqtt.subscriber.MdcBinder;
import com.opendota.mqtt.subscriber.MqttSubscriber;
import com.opendota.mqtt.subscriber.V2CHandler;
import com.opendota.mqtt.subscriber.V2CMessageDispatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * opendota-mqtt 自动装配。
 *
 * <p>总开关 {@code opendota.mqtt.enabled=true}(默认启用)。测试里若不想连 broker,可显式置 false。
 *
 * <p>装配顺序:
 * <ol>
 *   <li>{@link EnvelopeReader} / {@link EnvelopeWriter} —— Envelope 新读写入口</li>
 *   <li>{@link EnvelopeCodec} —— 兼容层,仅供旧调用点过渡</li>
 *   <li>{@link MqttClient} —— 连 broker,抛异常会让应用启动失败(fail-fast)</li>
 *   <li>{@link MqttPublisher} —— 无状态发布器</li>
 *   <li>{@link V2CMessageDispatcher} —— 收集所有 {@link V2CHandler} 并负责隔离 handler 异常</li>
 *   <li>{@link MqttSubscriber} —— 订阅 topic 并串联 EnvelopeReader / MdcBinder / Dispatcher</li>
 *   <li>{@link MqttLifecycle} —— 管 start / stop,与 Spring 容器生命周期同步</li>
 * </ol>
 */
@AutoConfiguration
@EnableConfigurationProperties(MqttProperties.class)
@ConditionalOnProperty(prefix = "opendota.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MqttAutoConfiguration.class);

    @Bean
    public EnvelopeReader envelopeReader(ObjectProvider<ObjectMapper> mapperProvider) {
        return new EnvelopeReader(mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    public EnvelopeWriter envelopeWriter(ObjectProvider<ObjectMapper> mapperProvider) {
        return new EnvelopeWriter(mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @SuppressWarnings("deprecation")
    public EnvelopeCodec envelopeCodec(ObjectProvider<ObjectMapper> mapperProvider,
                                       EnvelopeReader envelopeReader,
                                       EnvelopeWriter envelopeWriter) {
        return new EnvelopeCodec(
                mapperProvider.getIfAvailable(ObjectMapper::new),
                envelopeReader,
                envelopeWriter
        );
    }

    @Bean
    public MqttPublishMetrics mqttPublishMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        return new MqttPublishMetrics(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
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
    public MqttPublisher mqttPublisher(MqttClient client, EnvelopeWriter envelopeWriter,
                                       MqttProperties props, MqttPublishMetrics metrics) {
        return new MqttPublisher(client, envelopeWriter, props, metrics);
    }

    @Bean
    public MdcBinder mdcBinder() {
        return new MdcBinder();
    }

    @Bean
    public V2CMessageDispatcher v2cMessageDispatcher(ObjectProvider<V2CHandler> handlers) {
        return new V2CMessageDispatcher(List.copyOf(handlers.orderedStream().toList()));
    }

    @Bean
    public MqttSubscriber mqttSubscriber(MqttClient client, EnvelopeReader envelopeReader, MqttProperties props,
                                         V2CMessageDispatcher dispatcher, MdcBinder mdcBinder) {
        return new MqttSubscriber(client, envelopeReader, props, dispatcher, mdcBinder);
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
