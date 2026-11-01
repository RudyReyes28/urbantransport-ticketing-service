package com.urbantransport.ticketing.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResultEvent(UUID eventId, PaymentResultPayload payload) {

    /** reason es null cuando el pago se completó. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentResultPayload(String referenceType, UUID referenceId, String reason) {
    }
}