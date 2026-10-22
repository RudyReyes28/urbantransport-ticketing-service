package com.urbantransport.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketing.kafka")
public record TicketingKafkaProperties(
        String ticketEventsTopic,
        String tripEventsTopic,
        String paymentEventsTopic) {
}