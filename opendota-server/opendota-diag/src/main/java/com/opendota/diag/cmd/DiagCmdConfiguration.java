package com.opendota.diag.cmd;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 诊断命令模块 Bean 注册。
 */
@Configuration
public class DiagCmdConfiguration {

    @Bean
    public DiagCmdMetrics diagCmdMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        return new DiagCmdMetrics(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }
}
