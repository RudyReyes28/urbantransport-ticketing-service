package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class TicketDetailResponse {
    UUID ticketId;
    UUID orderId;
    UUID tripId;
    int seatNumber;
    String seatLabel;
    TicketStatus status;
    String cancelReason;
    BigDecimal pricePaid;
    SalesChannel channel;
    LocalDateTime purchasedAt;
    LocalDateTime cancelledAt;
    String originName;
    String destinationName;
    LocalDateTime departureAt;
    String busPlate;
    TripStatus tripStatus;

    public static TicketDetailResponse fromEntity(TicketEntity ticket, TripSnapshotEntity trip) {
        return new TicketDetailResponse(ticket.getId(), ticket.getOrderId(), ticket.getTripId(),
                ticket.getSeatNumber(), ticket.getSeatLabel(), ticket.getStatus(), ticket.getCancelReason(),
                ticket.getPricePaid(), ticket.getChannel(), ticket.getCreatedAt(), ticket.getCancelledAt(),
                trip.getOriginName(), trip.getDestinationName(), trip.getDepartureAt(), trip.getBusPlate(),
                trip.getStatus());
    }
}