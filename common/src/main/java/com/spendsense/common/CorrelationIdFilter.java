package com.spendsense.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id on every HTTP request.
 *
 * <p>The REST-only services (query, insights) have no upstream Kafka event to inherit an id
 * from, so one is minted here. An inbound {@code X-Correlation-Id} wins, which lets a caller
 * - the BFF, a script, a browser - thread its own id through and have the trace join up. The
 * id is echoed back on the response so the caller can quote it when something goes wrong.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(Correlation.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        Correlation.set(correlationId, null);
        response.setHeader(Correlation.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat hands this thread to the next request; without this the id leaks.
            Correlation.clear();
        }
    }
}
