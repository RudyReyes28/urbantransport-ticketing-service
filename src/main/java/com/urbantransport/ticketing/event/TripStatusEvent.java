package com.urbantransport.ticketing.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TripStatusEvent(UUID eventId, Instant occurredAt, TripRefPayload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripRefPayload(UUID tripId) {
    }
}