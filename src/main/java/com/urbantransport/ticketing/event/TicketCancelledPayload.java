package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class TicketCancelledPayload {

    /** Compra en línea: el reembolso a la cartera es automático. */
    public static final String REFUND_WALLET = "WALLET";
    /** Venta en mostrador: el reembolso en efectivo lo hace la sucursal. */
    public static final String REFUND_CASH = "CASH";

    UUID ticketId;
    UUID orderId;
    UUID tripId;
    int seatNumber;
    String seatLabel;
    String channel;
    UUID branchId;
    UUID customerId;
    String customerNit;
    String buyerEmail;        // null en mostrador
    BigDecimal pricePaid;
    String reason;
    String refundMethod;
    String originName;
    String destinationName;
    LocalDateTime departureAt;

    public static TicketCancelledPayload of(PurchaseOrderEntity order, TicketEntity ticket, TripSnapshotEntity trip,
                                            String reason, String refundMethod) {
        return new TicketCancelledPayload(ticket.getId(), order.getId(), order.getTripId(), ticket.getSeatNumber(),
                ticket.getSeatLabel(), order.getChannel().name(), order.getBranchId(), order.getCustomerId(),
                order.getCustomerNit(), order.getBuyerEmail(), ticket.getPricePaid(), reason, refundMethod,
                trip.getOriginName(), trip.getDestinationName(), trip.getDepartureAt());
    }
}