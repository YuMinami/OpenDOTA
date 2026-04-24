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
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqttAutoConfigurationTest {

    @Mock
    private MqttClient client;

    @Test
    void beanFactoriesCreateInfrastructureWithoutBrokerConnection() {
        MqttAutoConfiguration configuration = new MqttAutoConfiguration();
        DefaultListableBeanFactory mapperFactory = new DefaultListableBeanFactory();
        mapperFactory.registerSingleton("objectMapper", new ObjectMapper());

        EnvelopeReader envelopeReader = configuration.envelopeReader(mapperFactory.getBeanProvider(ObjectMapper.class));
        EnvelopeWriter envelopeWriter = configuration.envelopeWriter(mapperFactory.getBeanProvider(ObjectMapper.class));
        EnvelopeCodec envelopeCodec = configuration.envelopeCodec(
                mapperFactory.getBeanProvider(ObjectMapper.class),
                envelopeReader,
                envelopeWriter
        );
        DefaultListableBeanFactory registryFactory = new DefaultListableBeanFactory();
        MqttPublishMetrics metrics = configuration.mqttPublishMetrics(registryFactory.getBeanProvider(MeterRegistry.class));
        MdcBinder mdcBinder = configuration.mdcBinder();
        DefaultListableBeanFactory handlerFactory = new DefaultListableBeanFactory();
        handlerFactory.registerSingleton("handler", mock(V2CHandler.class));
        V2CMessageDispatcher dispatcher = configuration.v2cMessageDispatcher(handlerFactory.getBeanProvider(V2CHandler.class));
        MqttSubscriber subscriber = configuration.mqttSubscriber(
                client,
                envelopeReader,
                new MqttProperties(),
                dispatcher,
                mdcBinder
        );
        MqttPublisher publisher = configuration.mqttPublisher(client, envelopeWriter, new MqttProperties(), metrics);
        MqttAutoConfiguration.MqttLifecycle lifecycle = configuration.mqttLifecycle(client, subscriber);

        assertNotNull(envelopeReader);
        assertNotNull(envelopeWriter);
        assertNotNull(envelopeCodec);
        assertNotNull(metrics);
        assertNotNull(mdcBinder);
        assertEquals(1, dispatcher.handlerCount());
        assertNotNull(subscriber);
        assertNotNull(publisher);
        assertNotNull(lifecycle);
    }

    @Test
    void cloudMqttClientDelegatesToFactory() throws Exception {
        MqttAutoConfiguration configuration = new MqttAutoConfiguration();
        MqttProperties properties = new MqttProperties();

        try (MockedStatic<CloudMqttClientFactory> mockedFactory = mockStatic(CloudMqttClientFactory.class)) {
            mockedFactory.when(() -> CloudMqttClientFactory.build(properties)).thenReturn(client);

            assertSame(client, configuration.cloudMqttClient(properties));
            mockedFactory.verify(() -> CloudMqttClientFactory.build(properties));
        }
    }

    @Test
    void mqttLifecycleStartsSubscriberAndStopsConnectedClient() throws Exception {
        MqttSubscriber subscriber = mock(MqttSubscriber.class);
        when(client.isConnected()).thenReturn(true);
        MqttAutoConfiguration.MqttLifecycle lifecycle = new MqttAutoConfiguration.MqttLifecycle(client, subscriber);

        lifecycle.start();
        lifecycle.stop();

        verify(subscriber).start();
        verify(client).disconnect();
        verify(client).close();
    }

    @Test
    void mqttLifecycleSwallowsDisconnectAndCloseFailures() throws Exception {
        MqttSubscriber subscriber = mock(MqttSubscriber.class);
        when(client.isConnected()).thenReturn(true);
        doThrow(new MqttException(new RuntimeException("disconnect"))).when(client).disconnect();
        doThrow(new MqttException(new RuntimeException("close"))).when(client).close();
        MqttAutoConfiguration.MqttLifecycle lifecycle = new MqttAutoConfiguration.MqttLifecycle(client, subscriber);

        lifecycle.stop();

        verify(client).disconnect();
        verify(client).close();
    }
}
