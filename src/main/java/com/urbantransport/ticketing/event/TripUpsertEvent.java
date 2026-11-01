package com.urbantransport.ticketing.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.urbantransport.ticketing.domain.TripStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** TripScheduled y TripUpdated comparten la misma forma de payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TripUpsertEvent(UUID eventId, Instant occurredAt, TripPayload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripPayload(
            UUID tripId,
            String type,
            UUID routeId,
            UUID originBranchId,
            UUID destinationBranchId,
            String originName,
            String destinationName,
            UUID busId,
            String busPlate,
            Integer capacity,
            SeatLayoutPayload seatLayout,
            BigDecimal ticketPrice,
            LocalDateTime departureAt,
            LocalDateTime estimatedArrival,
            TripStatus status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatLayoutPayload(Integer rows, Integer columns, Integer aisleAfterColumn) {
    }
}