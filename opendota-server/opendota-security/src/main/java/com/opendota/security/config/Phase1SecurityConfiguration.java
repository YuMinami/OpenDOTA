package com.opendota.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 1 临时安全配置。
 *
 * <p>认证体系尚未接入 JWT / mTLS 主体解析前,先放行 DoD 所需的健康检查与基础验收接口,
 * 其余接口继续保留 Basic Auth 保护,避免默认 Spring Security 把所有入口都拦成 401。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class Phase1SecurityConfiguration {

    @Bean
    public SecurityFilterChain phase1SecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error", "/api/hello", "/actuator", "/actuator/health",
                                "/actuator/prometheus")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
