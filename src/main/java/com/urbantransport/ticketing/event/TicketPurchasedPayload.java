package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
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
public class TicketPurchasedPayload {
    UUID orderId;
    String channel;
    UUID branchId;
    UUID soldByUserId;     // null en compras en línea
    UUID customerId;
    String customerNit;
    String buyerEmail;     // null en mostrador
    LocalDate paymentDate;
    BigDecimal total;
    TripInfo trip;
    List<TicketInfo> tickets;

    @Value
    public static class TripInfo {
        UUID tripId;
        String originName;
        String destinationName;
        LocalDateTime departureAt;
        String busPlate;
    }

    @Value
    public static class TicketInfo {
        UUID ticketId;
        int seatNumber;
        String seatLabel;
        BigDecimal pricePaid;
    }

    public static TicketPurchasedPayload fromOrder(PurchaseOrderEntity order, TripSnapshotEntity trip) {
        List<TicketInfo> tickets = order.getTickets().stream()
                .sorted(Comparator.comparingInt(TicketEntity::getSeatNumber))
                .map(ticket -> new TicketInfo(ticket.getId(), ticket.getSeatNumber(), ticket.getSeatLabel(),
                        ticket.getPricePaid()))
                .toList();
        return new TicketPurchasedPayload(order.getId(), order.getChannel().name(), order.getBranchId(),
                order.getSoldByUserId(), order.getCustomerId(), order.getCustomerNit(), order.getBuyerEmail(),
                order.getPaymentDate(), order.getTotal(),
                new TripInfo(trip.getTripId(), trip.getOriginName(), trip.getDestinationName(),
                        trip.getDepartureAt(), trip.getBusPlate()),
                tickets);
    }
}