package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
public class TicketResponse {
    UUID ticketId;
    int seatNumber;
    String seatLabel;
    BigDecimal pricePaid;
    TicketStatus status;

    public static TicketResponse fromEntity(TicketEntity ticket) {
        return new TicketResponse(ticket.getId(), ticket.getSeatNumber(), ticket.getSeatLabel(),
                ticket.getPricePaid(), ticket.getStatus());
    }
}