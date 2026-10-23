package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Value
public class OrderResponse {
    UUID orderId;
    UUID tripId;
    SalesChannel channel;
    OrderStatus status;
    String originName;
    String destinationName;
    String busPlate;
    LocalDateTime departureAt;
    String customerNit;
    BigDecimal total;
    LocalDate paymentDate;
    LocalDateTime createdAt;
    LocalDateTime expiresAt;
    List<TicketResponse> tickets;

    public static OrderResponse fromEntity(PurchaseOrderEntity order, TripSnapshotEntity trip) {
        List<TicketResponse> tickets = order.getTickets().stream()
                .sorted(Comparator.comparingInt(TicketEntity::getSeatNumber))
                .map(TicketResponse::fromEntity)
                .toList();
        return new OrderResponse(order.getId(), order.getTripId(), order.getChannel(), order.getStatus(),
                trip.getOriginName(), trip.getDestinationName(), trip.getBusPlate(), trip.getDepartureAt(),
                order.getCustomerNit(), order.getTotal(), order.getPaymentDate(), order.getCreatedAt(),
                order.getExpiresAt(), tickets);
    }
}