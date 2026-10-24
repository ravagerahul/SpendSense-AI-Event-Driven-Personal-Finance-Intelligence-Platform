package com.spendsense.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the React dev server. Only the BFF needs this: the frontend talks to nothing else,
 * which is the point of the pattern, so the other services stay closed to browsers.
 *
 * <p>Origins come from configuration rather than being hard-coded, because the deployed
 * frontend will sit on a Vercel/Netlify domain rather than localhost.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${dashboard.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                // Lets the browser send its own trace id in and read back the one we used.
                .allowedHeaders("Content-Type", "X-Correlation-Id")
                .exposedHeaders("X-Correlation-Id");
    }
}
