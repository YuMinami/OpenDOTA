package com.opendota.simulator;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.List;

/**
 * 统一管理多辆模拟车的启停。
 *
 * <p>使用 Spring {@link SmartLifecycle} 而非 {@code @PostConstruct},
 * 保证在 EMQX / Kafka 等容器已 ready 之后再连 MQTT Broker。
 * {@code phase} 为较大值,最后启动、最先停止。
 */
public class SimulatorLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulatorLifecycle.class);

    private final List<MqttVehicleSimulator> simulators;
    private volatile boolean running = false;

    public SimulatorLifecycle(List<MqttVehicleSimulator> simulators) {
        this.simulators = simulators;
    }

    @Override
    public void start() {
        for (MqttVehicleSimulator sim : simulators) {
            try {
                sim.start();
            } catch (MqttException e) {
                log.error("启动模拟器失败 vin={}", sim.getVin(), e);
            }
        }
        running = true;
        if (!simulators.isEmpty()) {
            log.info("✅ 已启动 {} 辆模拟车,可通过 MQTT 或 DoIP 下发诊断", simulators.size());
        }
    }

    @Override
    public void stop() {
        for (MqttVehicleSimulator sim : simulators) {
            sim.stop();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 较大 phase 保证最晚启动,最早停止;避免 Broker 尚未 ready 时连失败
        return Integer.MAX_VALUE - 100;
    }

    public List<MqttVehicleSimulator> getSimulators() {
        return simulators;
    }
}
