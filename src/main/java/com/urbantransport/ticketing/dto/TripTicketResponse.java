package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class TripTicketResponse {
    UUID ticketId;
    UUID orderId;
    int seatNumber;
    String seatLabel;
    String customerNit;
    SalesChannel channel;
    TicketStatus status;
    String cancelReason;
    BigDecimal pricePaid;
    LocalDateTime purchasedAt;

    public static TripTicketResponse fromEntity(TicketEntity ticket) {
        return new TripTicketResponse(ticket.getId(), ticket.getOrderId(), ticket.getSeatNumber(),
                ticket.getSeatLabel(), ticket.getCustomerNit(), ticket.getChannel(), ticket.getStatus(),
                ticket.getCancelReason(), ticket.getPricePaid(), ticket.getCreatedAt());
    }
}