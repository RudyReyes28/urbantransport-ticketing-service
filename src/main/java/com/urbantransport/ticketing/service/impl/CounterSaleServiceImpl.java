package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.client.CustomerClient;
import com.urbantransport.ticketing.client.CustomerSummary;
import com.urbantransport.ticketing.config.TicketingProperties;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.SeatLayout;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.dto.CounterSaleRequest;
import com.urbantransport.ticketing.dto.OrderResponse;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.TicketPurchasedPayload;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.exception.SeatsUnavailableException;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.CounterSaleService;
import com.urbantransport.ticketing.service.OutboxService;
import com.urbantransport.ticketing.util.GenericCustomer;
import com.urbantransport.ticketing.util.NitUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * La consulta HTTP a customer se hace antes de abrir la transacción. Orden, boletos y evento del outbox se guardan
 * en una sola transacción: o ocurre todo o nada.
 */
@Service
@RequiredArgsConstructor
public class CounterSaleServiceImpl implements CounterSaleService {

    private final TripSnapshotRepository tripSnapshotRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TicketRepository ticketRepository;
    private final CustomerClient customerClient;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final TicketingProperties properties;
    private final Clock clock;

    private record CustomerRef(UUID id, String nit) {
    }

    @Override
    public OrderResponse sell(UUID userId, UUID branchId, String accessToken, CounterSaleRequest request) {
        List<Integer> seats = normalizeSeats(request.seats());
        LocalDate paymentDate = resolvePaymentDate(request.paymentDate());

        TripSnapshotEntity trip = loadSellableTrip(request.tripId(), branchId);
        validateSeatsInRange(trip, seats);
        CustomerRef customer = resolveCustomer(request.customerNit(), accessToken);

        try {
            return transactionTemplate.execute(status ->
                    persist(userId, branchId, request.tripId(), seats, paymentDate, customer));
        } catch (DataIntegrityViolationException exception) {
            // Otra venta tomó un asiento entre la verificación y el guardado (uq_active_seat)
            List<Integer> taken = takenAmong(request.tripId(), seats);
            if (taken.isEmpty()) {
                throw exception;
            }
            throw new SeatsUnavailableException(taken);
        }
    }

    private OrderResponse persist(UUID userId, UUID branchId, UUID tripId, List<Integer> seats,
                                  LocalDate paymentDate, CustomerRef customer) {
        // Se vuelve a leer dentro de la transacción: el viaje pudo cambiar mientras se consultaba a customer
        TripSnapshotEntity trip = loadSellableTrip(tripId, branchId);

        List<Integer> taken = takenAmong(tripId, seats);
        if (!taken.isEmpty()) {
            throw new SeatsUnavailableException(taken);
        }

        SeatLayout layout = trip.seatLayout();
        BigDecimal price = trip.getTicketPrice().setScale(2, RoundingMode.HALF_UP);
        LocalDateTime now = LocalDateTime.now(clock);

        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .tripId(tripId)
                .channel(SalesChannel.COUNTER)
                .customerId(customer.id())
                .customerNit(customer.nit())
                .branchId(branchId)
                .soldByUserId(userId)
                .total(price.multiply(BigDecimal.valueOf(seats.size())))
                .status(OrderStatus.CONFIRMED)
                .paymentDate(paymentDate)
                .confirmedAt(now)
                .build();
        for (int seat : seats) {
            order.addTicket(TicketEntity.builder()
                    .tripId(tripId)
                    .seatNumber(seat)
                    .seatLabel(layout.labelOf(seat))
                    .customerId(customer.id())
                    .customerNit(customer.nit())
                    .pricePaid(price)
                    .channel(SalesChannel.COUNTER)
                    .branchId(branchId)
                    .status(TicketStatus.CONFIRMED)
                    .build());
        }
        // saveAndFlush: si un asiento chocó, la violación de uq_active_seat salta aquí y se deshace todo
        purchaseOrderRepository.saveAndFlush(order);

        outboxService.record(EventTypes.AGGREGATE_ORDER, order.getId(), EventTypes.TICKET_PURCHASED,
                TicketPurchasedPayload.fromOrder(order, trip));
        return OrderResponse.fromEntity(order, trip);
    }

    // ---------- validaciones ----------

    private List<Integer> normalizeSeats(List<Integer> requested) {
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Hay asientos repetidos en la petición");
        }
        if (requested.size() > properties.maxSeatsPerOrder()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Se pueden vender hasta " + properties.maxSeatsPerOrder() + " asientos por orden");
        }
        return requested.stream().sorted().toList();
    }

    private LocalDate resolvePaymentDate(LocalDate requested) {
        LocalDate today = LocalDate.now(clock);
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La fecha de pago no puede ser futura");
        }
        return requested;
    }

    private TripSnapshotEntity loadSellableTrip(UUID tripId, UUID sellerBranchId) {
        TripSnapshotEntity trip = tripSnapshotRepository.findById(tripId)
                .filter(found -> found.getStatus() != TripStatus.DELETED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "El viaje no existe"));
        if (!trip.getOriginBranchId().equals(sellerBranchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo puedes vender viajes que salen de tu sucursal");
        }
        if (!trip.isSellable(LocalDateTime.now(clock))) {
            throw new ApiException(HttpStatus.CONFLICT, "El viaje ya no está disponible para la venta");
        }
        return trip;
    }

    private void validateSeatsInRange(TripSnapshotEntity trip, List<Integer> seats) {
        SeatLayout layout = trip.seatLayout();
        List<Integer> invalid = seats.stream().filter(seat -> !layout.contains(seat)).toList();
        if (!invalid.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Asientos fuera de rango (1 a " + layout.capacity() + "): " + invalid);
        }
    }

    private CustomerRef resolveCustomer(String rawNit, String accessToken) {
        String nit = rawNit == null || rawNit.isBlank() ? GenericCustomer.NIT : NitUtils.normalize(rawNit);
        if (GenericCustomer.NIT.equals(nit)) {
            return new CustomerRef(GenericCustomer.ID, GenericCustomer.NIT);
        }
        if (!NitUtils.isValid(nit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El NIT no es válido");
        }
        CustomerSummary customer = customerClient.findByNit(nit, accessToken)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "El cliente con NIT " + nit + " no está registrado; regístralo primero"));
        return new CustomerRef(customer.id(), customer.nit());
    }

    private List<Integer> takenAmong(UUID tripId, List<Integer> seats) {
        return ticketRepository.findSeatNumbersByTripIdAndStatusIn(tripId, TicketStatus.ACTIVE).stream()
                .filter(seats::contains)
                .sorted()
                .toList();
    }
}