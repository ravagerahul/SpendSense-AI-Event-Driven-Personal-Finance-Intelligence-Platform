package com.spendsense.dashboard;

import com.spendsense.common.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Forwards a request to another service and carries the trace with it.
 *
 * <p>The correlation id in the MDC - put there by the common module's filter when this request
 * arrived - is sent on as {@code X-Correlation-Id}. The receiving service's filter picks it up
 * instead of minting its own, which is what makes a chat question traceable from the browser
 * through the BFF into query-service as one id rather than two unrelated ones.
 *
 * <p>Only two endpoints proxy at all: the chat question, because the routing, embedding and SQL
 * generation live in query-service, and the review correction, because it writes the corrections
 * row that categorization-service later reads back as a few-shot example. Everything else on
 * this service is a read of Postgres, since forwarding a plain SELECT would add a hop and a
 * failure mode for nothing.
 */
@Component
public class ServiceProxy {

    private static final Logger log = LoggerFactory.getLogger(ServiceProxy.class);

    private final RestClient client;

    /**
     * Built with explicit timeouts, because {@code RestClient.create()} has none. A BFF that
     * waits forever on a downstream service holds a Tomcat thread per stuck request and takes
     * the frontend down with it, which is the opposite of what a single entry point is for.
     * The read timeout is deliberately generous: one chat question costs query-service four
     * Gemini calls.
     */
    public ServiceProxy(@Value("${dashboard.proxy.connect-timeout-ms:5000}") int connectTimeoutMs,
                        @Value("${dashboard.proxy.read-timeout-ms:90000}") int readTimeoutMs) {
        this.client = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String baseUrl, String path, Object body) {
        String correlationId = Correlation.currentCorrelationId();
        log.info("Proxying POST {}{} correlationId={}", baseUrl, path, correlationId);

        return client.post()
                .uri(baseUrl + path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (correlationId != null) h.set(Correlation.HEADER, correlationId);
                })
                .body(body)
                .retrieve()
                .body(Map.class);
    }
}
