package com.urbantransport.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(String businessZone, int maxSeatsPerOrder) {
}