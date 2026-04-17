package com.opendota;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OpenDotaApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenDotaApplication.class, args);
        System.out.println("============== OpenDOTA Platform 🚀 (Java 25 + SpringBoot 4.x) ==============");
        System.out.println("Status: MVP Server is running...");
        System.out.println("Listen: http://localhost:8080");
        System.out.println("==========================================================================");
    }
}
