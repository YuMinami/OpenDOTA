package com.opendota.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Java 版车端模拟器配置(架构 §8 + 部署 §2)。
 *
 * <p>通过 {@code opendota.simulator.enabled=true} 开启,默认关闭。
 * 生产环境严禁开启;生产 {@code application.yml} 显式置 false 兜底。
 *
 * <p>v1.4 A5/B1 交付物:解除 Phase 2 联调对 Rust Agent 的阻塞。
 */
@ConfigurationProperties(prefix = "opendota.simulator")
public class SimulatorProperties {

    /** 总开关。默认关;application-dev.yml 打开。 */
    private boolean enabled = false;

    /** MQTT Broker URL,默认指向本地 EMQX。 */
    private String brokerUrl = "tcp://localhost:1883";

    /** MQTT 用户名,单车场景一般等于 VIN。 */
    private String mqttUsername = "simulator";

    private String mqttPassword = "simulator";

    /** 模拟的车辆列表,每辆独立 MQTT 会话 + DoIP 监听。 */
    private List<VehicleProfile> vehicles = List.of();

    /** DoIP TCP 监听端口,默认 13400(ISO 13400 标准)。 */
    private int doipPort = 13400;

    /** DoIP 是否启用(CAN-only 场景可关)。 */
    private boolean doipEnabled = true;

    /** 模拟的 UDS 响应延迟(毫秒),下限。 */
    private int mockMinLatencyMs = 100;

    /** 模拟的 UDS 响应延迟(毫秒),上限;真实车端 1~3 秒。 */
    private int mockMaxLatencyMs = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBrokerUrl() { return brokerUrl; }
    public void setBrokerUrl(String brokerUrl) { this.brokerUrl = brokerUrl; }

    public String getMqttUsername() { return mqttUsername; }
    public void setMqttUsername(String mqttUsername) { this.mqttUsername = mqttUsername; }

    public String getMqttPassword() { return mqttPassword; }
    public void setMqttPassword(String mqttPassword) { this.mqttPassword = mqttPassword; }

    public List<VehicleProfile> getVehicles() { return vehicles; }
    public void setVehicles(List<VehicleProfile> vehicles) { this.vehicles = vehicles; }

    public int getDoipPort() { return doipPort; }
    public void setDoipPort(int doipPort) { this.doipPort = doipPort; }

    public boolean isDoipEnabled() { return doipEnabled; }
    public void setDoipEnabled(boolean doipEnabled) { this.doipEnabled = doipEnabled; }

    public int getMockMinLatencyMs() { return mockMinLatencyMs; }
    public void setMockMinLatencyMs(int mockMinLatencyMs) { this.mockMinLatencyMs = mockMinLatencyMs; }

    public int getMockMaxLatencyMs() { return mockMaxLatencyMs; }
    public void setMockMaxLatencyMs(int mockMaxLatencyMs) { this.mockMaxLatencyMs = mockMaxLatencyMs; }

    /**
     * 单辆模拟车的配置。
     */
    public static class VehicleProfile {
        /** 17 位 VIN,与 MQTT clientId / Topic 中的 VIN 段一致。 */
        private String vin;

        /** 诊断仪工位 ID,DoIP 场景承载(可空)。 */
        private String workstationId;

        /** 初始在线状态。默认 true。断网测试可设 false 后手动触发。 */
        private boolean startOnline = true;

        /** 时钟漂移模拟(毫秒),模拟 RTC 失信场景。0 表示时钟可信。 */
        private long clockDriftMs = 0;

        public String getVin() { return vin; }
        public void setVin(String vin) { this.vin = vin; }

        public String getWorkstationId() { return workstationId; }
        public void setWorkstationId(String workstationId) { this.workstationId = workstationId; }

        public boolean isStartOnline() { return startOnline; }
        public void setStartOnline(boolean startOnline) { this.startOnline = startOnline; }

        public long getClockDriftMs() { return clockDriftMs; }
        public void setClockDriftMs(long clockDriftMs) { this.clockDriftMs = clockDriftMs; }
    }
}
