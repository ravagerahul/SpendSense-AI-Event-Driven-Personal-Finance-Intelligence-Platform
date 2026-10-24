package com.spendsense.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This service's own health plus a ping of every other service, which makes one request enough
 * to see whether the whole platform is up - the reason the frontend has a single entry point in
 * the first place.
 *
 * <p>Replaces the common module's plain health endpoint, which stands down here via
 * {@code spendsense.health.shared-endpoint=false}. The same three fields - status, service,
 * timestamp - are still reported, so a caller that only knows the common contract is unaffected.
 */
@RestController
@RequestMapping("/api")
public class DashboardHealthController {

    private static final Logger log = LoggerFactory.getLogger(DashboardHealthController.class);

    private final ServiceRegistry registry;
    private final RestClient client;
    private final ExecutorService pool;

    public DashboardHealthController(ServiceRegistry registry,
                                     @Value("${dashboard.health.timeout-ms:2000}") int timeoutMs) {
        this.registry = registry;

        // A short timeout is the whole point: the default is minutes, so one hung service would
        // hang this endpoint exactly when someone is using it to find out what is wrong.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder().requestFactory(factory).build();

        // Pinged in parallel, so the endpoint costs one timeout in the worst case rather than
        // seven of them in sequence.
        this.pool = Executors.newFixedThreadPool(Math.max(1, registry.getServices().size()));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<Map.Entry<String, String>> targets = List.copyOf(registry.getServices().entrySet());

        List<CompletableFuture<Map<String, Object>>> futures = targets.stream()
                .map(e -> CompletableFuture.supplyAsync(() -> ping(e.getKey(), e.getValue()), pool))
                .toList();

        Map<String, Object> services = new LinkedHashMap<>();
        int up = 0;
        for (int i = 0; i < targets.size(); i++) {
            Map<String, Object> result = futures.get(i).join();
            if ("UP".equals(result.get("status"))) up++;
            services.put(targets.get(i).getKey(), result);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        // This service answered, so it is UP. Whether its dependencies are is a separate
        // question, and conflating the two would make the BFF look broken when query-service
        // is merely stopped.
        out.put("status", "UP");
        out.put("service", "dashboard-service");
        out.put("timestamp", Instant.now().toString());
        out.put("dependenciesUp", up + "/" + targets.size());
        out.put("services", services);
        return out;
    }

    private Map<String, Object> ping(String name, String baseUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", baseUrl);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client.get()
                    .uri(baseUrl + "/api/health")
                    .retrieve()
                    .body(Map.class);

            out.put("status", body == null ? "UNKNOWN" : String.valueOf(body.get("status")));
            // Reported back so a copy-pasted application.properties that points two services at
            // one port is visible here rather than being a mystery later.
            out.put("reportedName", body == null ? null : body.get("service"));
        } catch (Exception e) {
            log.warn("Health ping to {} at {} failed: {}", name, baseUrl, e.getMessage());
            out.put("status", "DOWN");
            out.put("error", e.getClass().getSimpleName());
        }
        return out;
    }
}
