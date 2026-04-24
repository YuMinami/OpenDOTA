package com.opendota.mqtt.subscriber;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.EnvelopeReader;
import com.opendota.mqtt.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MqttSubscriberTest {

    @Mock
    private MqttClient client;

    @Mock
    private V2CMessageDispatcher dispatcher;

    private MqttProperties props;
    private MqttSubscriber subscriber;

    @BeforeEach
    void setUp() {
        props = new MqttProperties();
        props.setTopicPrefix("dota/v1");
        props.setDefaultQos(1);
        subscriber = new MqttSubscriber(
                client,
                new EnvelopeReader(new ObjectMapper()),
                props,
                dispatcher,
                new MdcBinder()
        );
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void startSubscribesWildcardAndLegacyAckTopics() throws Exception {
        subscriber.start();

        verify(client).setCallback(subscriber);
        verify(client).subscribe("dota/v1/+/+/+", 1);
        verify(client).subscribe("dota/v1/ack/+", 1);
    }

    @Test
    void messageArrivedBindsMdcBeforeDispatchAndClearsAfterwards() throws Exception {
        doAnswer(invocation -> {
            assertEquals("12345678901234567890123456789012", MDC.get("traceId"));
            assertEquals("msg-001", MDC.get("msgId"));
            assertEquals("LSVWA234567890123", MDC.get("vin"));
            assertEquals("operator-1", MDC.get("operatorId"));
            assertEquals("tenant-1", MDC.get("tenantId"));
            return null;
        }).when(dispatcher).dispatch(eq("dota/v1/resp/single/LSVWA234567890123"), any());

        subscriber.messageArrived(
                "dota/v1/resp/single/LSVWA234567890123",
                new MqttMessage(validEnvelope())
        );

        verify(dispatcher).dispatch(
                eq("dota/v1/resp/single/LSVWA234567890123"),
                argThat(envelope -> envelope.act() == DiagAction.SINGLE_RESP)
        );
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("msgId"));
        assertNull(MDC.get("vin"));
        assertNull(MDC.get("operatorId"));
        assertNull(MDC.get("tenantId"));
    }

    @Test
    void messageArrivedDropsInvalidEnvelopeWithoutDispatch() throws Exception {
        subscriber.messageArrived("dota/v1/resp/single/LSVWA234567890123", new MqttMessage("{".getBytes()));

        verifyNoInteractions(dispatcher);
    }

    private byte[] validEnvelope() {
        return """
                {
                  "msgId":"msg-001",
                  "timestamp":1713301200000,
                  "vin":"LSVWA234567890123",
                  "act":"single_resp",
                  "operator":{
                    "id":"operator-1",
                    "role":"admin",
                    "tenantId":"tenant-1"
                  },
                  "traceparent":"00-12345678901234567890123456789012-1234567890123456-01",
                  "payload":{
                    "cmdId":"cmd-1",
                    "channelId":"ch-1",
                    "status":0,
                    "resData":"62F190",
                    "execDuration":18
                  }
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
