package com.haanh.ragassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class EnterpriseRagAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnterpriseRagAssistantApplication.class, args);
    }
}
