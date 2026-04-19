package com.opendota.mqtt.client;

import com.opendota.mqtt.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;

/**
 * 云端 Paho {@link MqttClient} 构建器。所有构造期参数从 {@link MqttProperties} 提取,
 * 便于单测 override 单个字段。
 *
 * <p>与车端(每辆车一个 clientId)不同,云端实例 clientId = {@code prefix + 8位UUID},
 * 以便同一节点重启不影响其它节点;集群下多节点共享订阅依赖 EMQX 的 {@code $share/<group>} Topic —— 这部分
 * 在 {@code MqttSubscriber} 侧通过 prefix 控制,本类只负责把单个物理连接打通。
 */
public final class CloudMqttClientFactory {

    private CloudMqttClientFactory() {}

    public static MqttClient build(MqttProperties props) throws MqttException {
        String clientId = props.getClientIdPrefix() + "-" + UUID.randomUUID().toString().substring(0, 8);
        MqttClient client = new MqttClient(props.getBrokerUrl(), clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(props.isCleanSession());
        opts.setAutomaticReconnect(true);
        opts.setConnectionTimeout(props.getConnectionTimeoutSeconds());
        opts.setKeepAliveInterval(props.getKeepAliveSeconds());
        opts.setUserName(props.getUsername());
        if (props.getPassword() != null) {
            opts.setPassword(props.getPassword().toCharArray());
        }

        client.connect(opts);
        return client;
    }
}
