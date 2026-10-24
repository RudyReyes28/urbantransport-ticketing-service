package com.urbantransport.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(
        String businessZone,
        int maxSeatsPerOrder,
        Duration orderTtl,
        int maxPendingOrdersPerCustomer) {
}