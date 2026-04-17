package com.opendota.diag.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/hello")
public class HelloController {

    @GetMapping
    public Map<String, Object> hello() {
        Map<String, Object> response = new HashMap<>();
        response.put("platform", "OpenDOTA");
        response.put("status", "running");
        response.put("message", "Hello World! 诊断架构后端已被成功唤醒。");
        // 简单展示当前线程是否为虚拟线程
        response.put("isVirtualThread", Thread.currentThread().isVirtual());
        return response;
    }
}
