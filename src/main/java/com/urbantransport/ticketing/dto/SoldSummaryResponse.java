package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.repository.TripSoldSummary;
import lombok.Value;

import java.util.UUID;

@Value
public class SoldSummaryResponse {
    UUID tripId;
    long activeTickets;
    /** null si el viaje no tiene boletos activos. */
    Integer maxSeatNumber;

    public static SoldSummaryResponse of(UUID tripId, TripSoldSummary summary) {
        return new SoldSummaryResponse(tripId,
                summary.activeTickets() == null ? 0 : summary.activeTickets(), summary.maxSeatNumber());
    }
}