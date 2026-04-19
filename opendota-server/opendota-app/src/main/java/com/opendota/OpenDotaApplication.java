package com.opendota;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OpenDOTA 车云通讯诊断平台主入口。
 *
 * <p>技术栈基线(CLAUDE.md / tech_architecture §1.1):Spring Boot 3.4.2 + Java 21 LTS + 虚拟线程。
 *
 * <p>启动方式:
 * <pre>
 *   # 生产-like(需先 docker-compose up postgres emqx redis kafka)
 *   mvn spring-boot:run -pl opendota-app -am
 *
 *   # dev profile(自动装载 Java 车端模拟器 + mock 数据)
 *   mvn spring-boot:run -pl opendota-app -am -Dspring-boot.run.profiles=dev
 * </pre>
 */
@SpringBootApplication
public class OpenDotaApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenDotaApplication.class, args);
        System.out.println("============== OpenDOTA Platform 🚀 (Java 21 LTS + SpringBoot 3.4.2) ==============");
        System.out.println("Status: Server is running...");
        System.out.println("Listen: http://localhost:8080");
        System.out.println("Docs  : http://localhost:8080/swagger-ui.html (待 springdoc 装配后可用)");
        System.out.println("===================================================================================");
    }
}

