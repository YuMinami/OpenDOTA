package com.opendota.mqtt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 云端 MQTT 客户端配置(协议 §2 + 架构 §3.2)。
 *
 * <p>绑定前缀 {@code opendota.mqtt}。默认值面向本地联调(EMQX {@code tcp://localhost:1883}),
 * 生产通过 {@code OPENDOTA_MQTT_*} 环境变量覆盖。
 *
 * <p>开关 {@link #enabled} 在单元测试里置 {@code false} 可以跳过整个 MQTT 子系统装配,
 * 避免 CI 环境没有 broker 时启动失败。
 */
@ConfigurationProperties(prefix = "opendota.mqtt")
public class MqttProperties {

    /** 总开关;CI/测试可在 {@code application-test.yml} 里关闭。 */
    private boolean enabled = true;

    /** Broker URL,例如 {@code tcp://localhost:1883}、{@code ssl://emqx.example:8883}。 */
    private String brokerUrl = "tcp://localhost:1883";

    /** Topic 前缀,生产/测试可以区分 {@code dota/v1} / {@code dota/v1-staging}。 */
    private String topicPrefix = "dota/v1";

    /** Client ID 前缀,实际 clientId 会拼一个短 UUID 段,保证多节点互不冲突。 */
    private String clientIdPrefix = "opendota-cloud";

    /** 云端 MQTT 账号;生产通过 mTLS 时仍建议填用户名便于 EMQX ACL 审计。 */
    private String username = "opendota-cloud";

    /** MQTT 密码;测试默认,生产通过环境变量覆盖。 */
    private String password = "opendota-cloud";

    /** 连接超时(秒)。 */
    private int connectionTimeoutSeconds = 10;

    /** Keep-Alive 心跳(秒)。 */
    private int keepAliveSeconds = 60;

    /** 默认 QoS;云车双向统一 QoS 1(协议 §2.1)。 */
    private int defaultQos = 1;

    /** Clean Session;保持 false 以便断网期间订阅的 retained QoS1 消息仍能送达。 */
    private boolean cleanSession = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBrokerUrl() { return brokerUrl; }
    public void setBrokerUrl(String brokerUrl) { this.brokerUrl = brokerUrl; }

    public String getTopicPrefix() { return topicPrefix; }
    public void setTopicPrefix(String topicPrefix) { this.topicPrefix = topicPrefix; }

    public String getClientIdPrefix() { return clientIdPrefix; }
    public void setClientIdPrefix(String clientIdPrefix) { this.clientIdPrefix = clientIdPrefix; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getConnectionTimeoutSeconds() { return connectionTimeoutSeconds; }
    public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }

    public int getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }

    public int getDefaultQos() { return defaultQos; }
    public void setDefaultQos(int defaultQos) { this.defaultQos = defaultQos; }

    public boolean isCleanSession() { return cleanSession; }
    public void setCleanSession(boolean cleanSession) { this.cleanSession = cleanSession; }
}
