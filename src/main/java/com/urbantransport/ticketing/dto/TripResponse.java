package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class TripResponse {
    UUID tripId;
    UUID originBranchId;
    UUID destinationBranchId;
    String originName;
    String destinationName;
    String busPlate;
    LocalDateTime departureAt;
    LocalDateTime estimatedArrival;
    BigDecimal ticketPrice;
    int capacity;
    int availableSeats;

    public static TripResponse fromEntity(TripSnapshotEntity trip, int availableSeats) {
        return new TripResponse(trip.getTripId(), trip.getOriginBranchId(), trip.getDestinationBranchId(),
                trip.getOriginName(), trip.getDestinationName(), trip.getBusPlate(), trip.getDepartureAt(),
                trip.getEstimatedArrival(), trip.getTicketPrice(), trip.getCapacity(), availableSeats);
    }
}