package com.spendsense.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health, identical in every service. The name comes from
 * {@code spring.application.name}, so a service cannot report someone else's name after a
 * copy-paste.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final String service;

    public HealthController(@Value("${spring.application.name:unknown}") String service) {
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("service", service);
        out.put("timestamp", Instant.now().toString());
        return out;
    }
}
