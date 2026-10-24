package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.RefundRequestedPayload;
import com.urbantransport.ticketing.event.TicketCancelledPayload;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.OutboxService;
import com.urbantransport.ticketing.service.TripCascadeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Corre dentro de la transacción del evento de viaje (por eso exige transacción activa): estados de las órdenes,
 * boletos y eventos del outbox se guardan juntos. Las órdenes se bloquean para no pisarse con un pago en curso.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripCascadeServiceImpl implements TripCascadeService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TripSnapshotRepository tripSnapshotRepository;
    private final OutboxService outboxService;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void expirePendingOrders(UUID tripId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PurchaseOrderEntity> pending =
                purchaseOrderRepository.findByTripIdAndStatusInForUpdate(tripId, List.of(OrderStatus.PENDING));
        for (PurchaseOrderEntity order : pending) {
            order.setStatus(OrderStatus.EXPIRED);
            order.setFailureReason(CancelReason.TRIP_STARTED);
            order.cancelPendingTickets(CancelReason.TRIP_STARTED, now);
        }
        if (!pending.isEmpty()) {
            log.info("Viaje {} iniciado: {} órdenes pendientes expiradas", tripId, pending.size());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelTripOrders(UUID tripId) {
        TripSnapshotEntity trip = tripSnapshotRepository.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("Viaje " + tripId + " sin snapshot"));
        LocalDateTime now = LocalDateTime.now(clock);

        List<PurchaseOrderEntity> orders = purchaseOrderRepository.findByTripIdAndStatusInForUpdate(
                tripId, List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED));
        int charged = 0;
        for (PurchaseOrderEntity order : orders) {
            List<TicketEntity> paid = order.cancelActiveTickets(CancelReason.TRIP_DELETED, now);
            order.setStatus(OrderStatus.CANCELLED);
            order.setFailureReason(CancelReason.TRIP_DELETED);
            for (TicketEntity ticket : paid) {
                announceCancellation(order, ticket, trip);
                charged++;
            }
        }
        log.info("Viaje {} eliminado: {} órdenes canceladas, {} boletos cobrados a reembolsar",
                tripId, orders.size(), charged);
    }

    /** TicketCancelled para todos; RefundRequested solo para los de línea (los de mostrador se devuelven en efectivo). */
    private void announceCancellation(PurchaseOrderEntity order, TicketEntity ticket, TripSnapshotEntity trip) {
        boolean online = order.getChannel() == SalesChannel.ONLINE;
        outboxService.record(EventTypes.AGGREGATE_TICKET, ticket.getId(), EventTypes.TICKET_CANCELLED,
                TicketCancelledPayload.of(order, ticket, trip, CancelReason.TRIP_DELETED,
                        online ? TicketCancelledPayload.REFUND_WALLET : TicketCancelledPayload.REFUND_CASH));
        if (online) {
            outboxService.record(EventTypes.AGGREGATE_TICKET, ticket.getId(), EventTypes.REFUND_REQUESTED,
                    new RefundRequestedPayload(ticket.getId(), order.getCustomerId(), order.getCustomerNit(),
                            ticket.getPricePaid(), order.getId(), ticket.getSeatLabel()));
        }
    }
}