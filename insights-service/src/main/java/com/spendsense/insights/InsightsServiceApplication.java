package com.spendsense.insights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InsightsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InsightsServiceApplication.class, args);
    }
}
