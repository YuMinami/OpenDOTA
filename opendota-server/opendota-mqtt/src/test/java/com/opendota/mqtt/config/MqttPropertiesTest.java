package com.opendota.mqtt.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttPropertiesTest {

    @Test
    void defaultsMatchLocalDevelopmentContract() {
        MqttProperties properties = new MqttProperties();

        assertTrue(properties.isEnabled());
        assertEquals("tcp://localhost:1883", properties.getBrokerUrl());
        assertEquals("dota/v1", properties.getTopicPrefix());
        assertEquals("opendota-cloud", properties.getClientIdPrefix());
        assertEquals("opendota-cloud", properties.getUsername());
        assertEquals("opendota-cloud", properties.getPassword());
        assertEquals(10, properties.getConnectionTimeoutSeconds());
        assertEquals(60, properties.getKeepAliveSeconds());
        assertEquals(1, properties.getDefaultQos());
        assertFalse(properties.isCleanSession());
    }

    @Test
    void settersOverrideDefaults() {
        MqttProperties properties = new MqttProperties();

        properties.setEnabled(false);
        properties.setBrokerUrl("ssl://emqx.example:8883");
        properties.setTopicPrefix("dota/v1-staging");
        properties.setClientIdPrefix("cloud-staging");
        properties.setUsername("mqtt-user");
        properties.setPassword("mqtt-pass");
        properties.setConnectionTimeoutSeconds(30);
        properties.setKeepAliveSeconds(120);
        properties.setDefaultQos(2);
        properties.setCleanSession(true);

        assertFalse(properties.isEnabled());
        assertEquals("ssl://emqx.example:8883", properties.getBrokerUrl());
        assertEquals("dota/v1-staging", properties.getTopicPrefix());
        assertEquals("cloud-staging", properties.getClientIdPrefix());
        assertEquals("mqtt-user", properties.getUsername());
        assertEquals("mqtt-pass", properties.getPassword());
        assertEquals(30, properties.getConnectionTimeoutSeconds());
        assertEquals(120, properties.getKeepAliveSeconds());
        assertEquals(2, properties.getDefaultQos());
        assertTrue(properties.isCleanSession());
    }
}
