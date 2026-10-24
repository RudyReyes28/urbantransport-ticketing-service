package com.urbantransport.ticketing.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundCompletedEvent(UUID eventId, RefundCompletedPayload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundCompletedPayload(UUID ticketId, BigDecimal amount) {
    }
}