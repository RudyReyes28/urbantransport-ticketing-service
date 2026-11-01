package com.urbantransport.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ticketing.customer")
public record CustomerClientProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        CircuitBreakerProperties circuitBreaker) {

    public record CircuitBreakerProperties(
            int slidingWindowSize,
            int minimumCalls,
            float failureRateThreshold,
            Duration waitInOpenState) {
    }
}