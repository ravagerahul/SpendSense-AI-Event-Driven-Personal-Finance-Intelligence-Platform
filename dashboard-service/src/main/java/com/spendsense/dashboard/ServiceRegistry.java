package com.spendsense.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The other services' base URLs, from {@code dashboard.services.*}. Configuration rather than
 * constants because every one of these becomes a different host once the services are deployed
 * as separate containers.
 */
@Component
@ConfigurationProperties(prefix = "dashboard")
public class ServiceRegistry {

    private Map<String, String> services = new LinkedHashMap<>();

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }
}
