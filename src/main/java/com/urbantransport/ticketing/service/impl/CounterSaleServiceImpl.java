package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.client.CustomerClient;
import com.urbantransport.ticketing.client.CustomerSummary;
import com.urbantransport.ticketing.domain.*;
import com.urbantransport.ticketing.dto.*;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.TicketPurchasedPayload;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.exception.SeatsUnavailableException;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.service.*;
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
import java.util.List;
import java.util.UUID;

/**
 * La consulta HTTP a customer se hace antes de abrir la transacción. Orden, boletos y evento del outbox se guardan
 * en una sola transacción: o ocurre todo o nada.
 */
@Service
@RequiredArgsConstructor
public class CounterSaleServiceImpl implements CounterSaleService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final CustomerClient customerClient;
    private final OutboxService outboxService;
    private final OrderRules rules;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    private record CustomerRef(UUID id, String nit) {
    }

    @Override
    public OrderResponse sell(UUID userId, UUID branchId, String accessToken, CounterSaleRequest request) {
        List<Integer> seats = rules.normalizeSeats(request.seats());
        LocalDate paymentDate = rules.resolvePaymentDate(request.paymentDate());

        TripSnapshotEntity trip = loadTripOfBranch(request.tripId(), branchId);
        rules.validateSeatsInRange(trip, seats);
        CustomerRef customer = resolveCustomer(request.customerNit(), accessToken);

        try {
            return transactionTemplate.execute(status ->
                    persist(userId, branchId, request.tripId(), seats, paymentDate, customer));
        } catch (DataIntegrityViolationException exception) {
            // Otra venta tomó un asiento entre la verificación y el guardado (uq_active_seat)
            List<Integer> taken = rules.takenAmong(request.tripId(), seats);
            if (taken.isEmpty()) {
                throw exception;
            }
            throw new SeatsUnavailableException(taken);
        }
    }

    private OrderResponse persist(UUID userId, UUID branchId, UUID tripId, List<Integer> seats,
                                  LocalDate paymentDate, CustomerRef customer) {
        // Se vuelve a leer dentro de la transacción: el viaje pudo cambiar mientras se consultaba a customer
        TripSnapshotEntity trip = loadTripOfBranch(tripId, branchId);
        rules.assertSeatsFree(tripId, seats);

        SeatLayout layout = trip.seatLayout();
        BigDecimal price = trip.getTicketPrice().setScale(2, RoundingMode.HALF_UP);

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
                .confirmedAt(LocalDateTime.now(clock))
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

    /** Existe (404), sale de la sucursal del vendedor (403) y es vendible (409), en ese orden. */
    private TripSnapshotEntity loadTripOfBranch(UUID tripId, UUID sellerBranchId) {
        TripSnapshotEntity trip = rules.findTrip(tripId);
        if (!trip.getOriginBranchId().equals(sellerBranchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo puedes vender viajes que salen de tu sucursal");
        }
        rules.requireSellable(trip);
        return trip;
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
}