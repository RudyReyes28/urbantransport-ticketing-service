package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.ProcessedEventEntity;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.event.*;
import com.urbantransport.ticketing.repository.ProcessedEventRepository;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.OrderPaymentService;
import com.urbantransport.ticketing.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * El cambio de estado, los eventos del outbox y el registro del eventId se guardan en una sola transacción. La
 * orden se bloquea (FOR UPDATE) antes de decidir, para no pisarse con la tarea de expiración. Los errores de datos
 * lanzan excepción para que el mensaje vaya al DLT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentServiceImpl implements OrderPaymentService {

    private static final int MAX_FAILURE_REASON = 40;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TripSnapshotRepository tripSnapshotRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxService outboxService;
    private final Clock clock;
    private final TicketRepository ticketRepository;

    @Override
    @Transactional
    public void handlePaymentCompleted(UUID eventId, PaymentResultEvent.PaymentResultPayload payload) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        validate(payload);
        if (!EventTypes.REFERENCE_ORDER.equals(payload.referenceType())) {
            log.debug("Pago {} de tipo {} ignorado: no es una orden de boletos", eventId, payload.referenceType());
            return;
        }

        PurchaseOrderEntity order = lockOrder(payload);
        switch (order.getStatus()) {
            case PENDING -> confirm(order);
            case CONFIRMED -> log.debug("Orden {} ya confirmada, pago duplicado ignorado", order.getId());
            default -> refundLatePayment(order);
        }
        markProcessed(eventId);
    }

    @Override
    @Transactional
    public void handlePaymentFailed(UUID eventId, PaymentResultEvent.PaymentResultPayload payload) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        validate(payload);
        if (!EventTypes.REFERENCE_ORDER.equals(payload.referenceType())) {
            log.debug("Pago fallido {} de tipo {} ignorado: no es una orden de boletos", eventId, payload.referenceType());
            return;
        }

        PurchaseOrderEntity order = lockOrder(payload);
        if (order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason(failureReason(payload.reason()));
            order.cancelPendingTickets(CancelReason.PAYMENT_FAILED, LocalDateTime.now(clock));
        } else {
            log.debug("Orden {} en estado {}, el fallo de pago se ignora", order.getId(), order.getStatus());
        }
        markProcessed(eventId);
    }

    @Override
    @Transactional
    public void handleRefundCompleted(UUID eventId, RefundCompletedEvent.RefundCompletedPayload payload) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        if (payload == null || payload.ticketId() == null) {
            throw new IllegalArgumentException("RefundCompleted sin ticketId");
        }

        Optional<TicketEntity> found = ticketRepository.findById(payload.ticketId());
        if (found.isEmpty()) {
            // Reembolso de otro servicio (p. ej. alquileres): no es un boleto de ticketing
            log.debug("Reembolso {} de {} ignorado: no es un boleto", eventId, payload.ticketId());
            return;
        }
        TicketEntity ticket = found.get();
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            ticket.setStatus(TicketStatus.REFUNDED);
        } else {
            log.debug("Boleto {} en estado {}, el reembolso se ignora", ticket.getId(), ticket.getStatus());
        }
        markProcessed(eventId);
    }

    // ---------- transiciones ----------

    private void confirm(PurchaseOrderEntity order) {
        // Aunque ya pasó expires_at, si la tarea de expiración aún no la tocó se confirma: el cliente ya pagó
        order.setStatus(OrderStatus.CONFIRMED);
        order.setConfirmedAt(LocalDateTime.now(clock));
        order.confirmPendingTickets();

        TripSnapshotEntity trip = tripSnapshotRepository.findById(order.getTripId())
                .orElseThrow(() -> new IllegalStateException("Orden " + order.getId() + " sin viaje"));
        outboxService.record(EventTypes.AGGREGATE_ORDER, order.getId(), EventTypes.TICKET_PURCHASED,
                TicketPurchasedPayload.fromOrder(order, trip));
    }

    /** Se cobró, pero la orden ya no está pendiente: se devuelve el precio de cada boleto cancelado. */
    private void refundLatePayment(PurchaseOrderEntity order) {
        log.warn("Pago recibido para la orden {} en estado {}: se solicita el reembolso", order.getId(), order.getStatus());
        for (TicketEntity ticket : order.getTickets()) {
            if (ticket.getStatus() != TicketStatus.CANCELLED) {
                continue;
            }
            outboxService.record(EventTypes.AGGREGATE_TICKET, ticket.getId(), EventTypes.REFUND_REQUESTED,
                    new RefundRequestedPayload(ticket.getId(), order.getCustomerId(), order.getCustomerNit(),
                            ticket.getPricePaid(), order.getId(), ticket.getSeatLabel()));
        }
    }

    // ---------- utilidades ----------

    private PurchaseOrderEntity lockOrder(PaymentResultEvent.PaymentResultPayload payload) {
        return purchaseOrderRepository.findByIdForUpdate(payload.referenceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Resultado de pago de una orden inexistente: " + payload.referenceId()));
    }

    private void validate(PaymentResultEvent.PaymentResultPayload payload) {
        if (payload == null || payload.referenceType() == null || payload.referenceId() == null) {
            throw new IllegalArgumentException("Resultado de pago sin referenceType o referenceId");
        }
    }

    private String failureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        return reason.length() <= MAX_FAILURE_REASON ? reason : reason.substring(0, MAX_FAILURE_REASON);
    }

    private void markProcessed(UUID eventId) {
        processedEventRepository.save(ProcessedEventEntity.builder().eventId(eventId).build());
    }
}