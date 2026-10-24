package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.config.TicketingProperties;
import com.urbantransport.ticketing.domain.*;
import com.urbantransport.ticketing.dto.CreateOrderRequest;
import com.urbantransport.ticketing.dto.OrderResponse;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.PaymentRequestedPayload;
import com.urbantransport.ticketing.exception.*;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orden, boletos y evento PaymentRequested (outbox) se guardan en una sola transacción. El resultado del pago llega
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TripSnapshotRepository tripSnapshotRepository;
    private final OrderRules rules;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final TicketingProperties properties;
    private final Clock clock;

    @Override
    public OrderResponse create(UUID customerId, String customerNit, String buyerEmail, CreateOrderRequest request,
                                String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        List<Integer> seats = rules.normalizeSeats(request.seats());
        LocalDate paymentDate = rules.resolvePaymentDate(request.paymentDate());

        if (key != null) {
            Optional<OrderResponse> replay = findReplay(customerId, key);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        try {
            return transactionTemplate.execute(status ->
                    persist(customerId, customerNit, buyerEmail, request.tripId(), seats, paymentDate, key));
        } catch (DataIntegrityViolationException exception) {
            // Dos casos: la misma clave llegó dos veces a la vez, o alguien tomó un asiento (uq_active_seat)
            if (key != null) {
                Optional<OrderResponse> replay = findReplay(customerId, key);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            List<Integer> taken = rules.takenAmong(request.tripId(), seats);
            if (taken.isEmpty()) {
                throw exception;
            }
            throw new SeatsUnavailableException(taken);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getForCustomer(UUID orderId, UUID customerId) {
        PurchaseOrderEntity order = purchaseOrderRepository.findById(orderId)
                .filter(found -> found.getCustomerId().equals(customerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "La orden no existe"));
        return OrderResponse.fromEntity(order, loadTrip(order));
    }

    private OrderResponse persist(UUID customerId, String customerNit, String buyerEmail, UUID tripId,
                                  List<Integer> seats, LocalDate paymentDate, String key) {
        TripSnapshotEntity trip = rules.findTrip(tripId);
        rules.requireSellable(trip);
        rules.validateSeatsInRange(trip, seats);

        long pending = purchaseOrderRepository.countByCustomerIdAndStatus(customerId, OrderStatus.PENDING);
        if (pending >= properties.maxPendingOrdersPerCustomer()) {
            throw new ApiException(HttpStatus.CONFLICT, "Tienes demasiadas órdenes pendientes de pago ("
                    + pending + "); espera a que se completen o expiren");
        }
        rules.assertSeatsFree(tripId, seats);

        SeatLayout layout = trip.seatLayout();
        BigDecimal price = trip.getTicketPrice().setScale(2, RoundingMode.HALF_UP);

        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .tripId(tripId)
                .channel(SalesChannel.ONLINE)
                .customerId(customerId)
                .customerNit(customerNit)
                .buyerEmail(buyerEmail)
                .branchId(trip.getOriginBranchId())   // el ingreso se atribuye a la sucursal de origen del viaje
                .total(price.multiply(BigDecimal.valueOf(seats.size())))
                .status(OrderStatus.PENDING)
                .idempotencyKey(key)
                .paymentDate(paymentDate)
                .expiresAt(LocalDateTime.now(clock).plus(properties.orderTtl()))
                .build();
        for (int seat : seats) {
            order.addTicket(TicketEntity.builder()
                    .tripId(tripId)
                    .seatNumber(seat)
                    .seatLabel(layout.labelOf(seat))
                    .customerId(customerId)
                    .customerNit(customerNit)
                    .pricePaid(price)
                    .channel(SalesChannel.ONLINE)
                    .branchId(trip.getOriginBranchId())
                    .status(TicketStatus.PENDING)
                    .build());
        }
        // saveAndFlush: las violaciones de uq_active_seat y uq_orders_idempotency saltan aquí y se deshace todo
        purchaseOrderRepository.saveAndFlush(order);

        outboxService.record(EventTypes.AGGREGATE_ORDER, order.getId(), EventTypes.PAYMENT_REQUESTED,
                new PaymentRequestedPayload(EventTypes.REFERENCE_ORDER, order.getId(), customerId, customerNit,
                        order.getTotal(), paymentDate));
        return OrderResponse.fromEntity(order, trip);
    }

    private Optional<OrderResponse> findReplay(UUID customerId, String key) {
        return transactionTemplate.execute(status ->
                purchaseOrderRepository.findByCustomerIdAndIdempotencyKey(customerId, key)
                        .map(order -> OrderResponse.fromEntity(order, loadTrip(order))));
    }

    private TripSnapshotEntity loadTrip(PurchaseOrderEntity order) {
        return tripSnapshotRepository.findById(order.getTripId())
                .orElseThrow(() -> new IllegalStateException("Orden " + order.getId() + " sin viaje"));
    }

    private String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key inválida: use de 1 a 64 letras, números, guiones o guiones bajos");
        }
        return key;
    }
}