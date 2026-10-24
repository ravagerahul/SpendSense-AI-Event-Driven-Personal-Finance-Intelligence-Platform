package com.spendsense.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wires the same observability into all seven services without any of them having to ask.
 *
 * <p>Registered through {@code AutoConfiguration.imports} rather than component scanning,
 * because each service scans its own {@code com.spendsense.<service>} package and would
 * otherwise never see this one. The alternative - seven copies of the same filter, the same
 * interceptor and the same health endpoint - is exactly the thing that drifts apart.
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    /**
     * The plain health endpoint. dashboard-service sets
     * {@code spendsense.health.shared-endpoint=false} and serves its own, which reports the
     * same three fields and adds a ping to every other service; without the switch the two
     * would collide on the same mapping and the service would refuse to start.
     */
    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "spendsense.health.shared-endpoint", havingValue = "true", matchIfMissing = true)
    public HealthController spendSenseHealthController(
            @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}")
            String service) {
        return new HealthController(service);
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnClass(OncePerRequestFilter.class)
    public org.springframework.boot.web.servlet.FilterRegistrationBean<CorrelationIdFilter>
            correlationIdFilterRegistration() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new CorrelationIdFilter());
        // First in the chain: anything logged by a later filter should already carry the id.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Spring Boot's Kafka auto-configuration picks up a single {@code RecordInterceptor} bean
     * and installs it on the default listener container factory, so every {@code @KafkaListener}
     * in the service - including the retry and DLT containers - is covered by this one bean.
     */
    @Bean
    @ConditionalOnClass(RecordInterceptor.class)
    @ConditionalOnMissingBean(RecordInterceptor.class)
    public RecordInterceptor<Object, Object> correlationRecordInterceptor(ObjectMapper mapper) {
        return new CorrelationRecordInterceptor(mapper);
    }
}
