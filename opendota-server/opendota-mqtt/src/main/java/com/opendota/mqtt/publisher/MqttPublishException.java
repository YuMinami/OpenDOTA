package com.opendota.mqtt.publisher;

/**
 * MQTT 发布失败异常。
 */
public class MqttPublishException extends RuntimeException {

    public MqttPublishException(String message) {
        super(message);
    }

    public MqttPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
