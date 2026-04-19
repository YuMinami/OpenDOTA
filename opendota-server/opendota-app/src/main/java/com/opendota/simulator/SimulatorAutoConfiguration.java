package com.opendota.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟器自动装配。
 *
 * <p>仅在 {@code opendota.simulator.enabled=true} 时生效。为每辆 {@link SimulatorProperties.VehicleProfile}
 * 注册一个 {@link MqttVehicleSimulator} bean;若 {@code doipEnabled=true} 额外启动一个共享的
 * {@link DoipMockServer}(单端口,所有模拟车共用 DoIP 响应表)。
 *
 * <p>生产环境约定:{@code application.yml} 中必须显式声明 {@code opendota.simulator.enabled: false},
 * 由运维在 CI 上加 grep 兜底,防止模拟器被误部署到线上。
 */
@AutoConfiguration
@EnableConfigurationProperties(SimulatorProperties.class)
@ConditionalOnProperty(prefix = "opendota.simulator", name = "enabled", havingValue = "true")
public class SimulatorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SimulatorAutoConfiguration.class);

    @Bean
    public CanMockResponder canMockResponder() {
        return new CanMockResponder();
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "opendota.simulator", name = "doip-enabled", havingValue = "true",
            matchIfMissing = true)
    public DoipMockServer doipMockServer(SimulatorProperties props, CanMockResponder canResponder) {
        log.info("🛠 启用 DoIP 模拟器: port={}", props.getDoipPort());
        return new DoipMockServer(props.getDoipPort(), canResponder);
    }

    /**
     * 为每辆 VehicleProfile 注册独立的 MQTT 模拟器。
     * 返回 List 便于 OpenDotaApplication 打印启动日志,不直接被业务代码消费。
     */
    @Bean
    public SimulatorLifecycle simulatorLifecycle(
            SimulatorProperties props,
            CanMockResponder canResponder,
            ObjectProvider<ObjectMapper> mapperProvider) {

        ObjectMapper mapper = mapperProvider.getIfAvailable(ObjectMapper::new);
        List<MqttVehicleSimulator> simulators = new ArrayList<>();
        if (props.getVehicles().isEmpty()) {
            log.warn("opendota.simulator.enabled=true 但未配置 vehicles 列表;模拟器空跑");
        }
        for (SimulatorProperties.VehicleProfile profile : props.getVehicles()) {
            MqttVehicleSimulator sim = new MqttVehicleSimulator(props, profile, canResponder, mapper);
            simulators.add(sim);
        }
        return new SimulatorLifecycle(simulators);
    }
}
