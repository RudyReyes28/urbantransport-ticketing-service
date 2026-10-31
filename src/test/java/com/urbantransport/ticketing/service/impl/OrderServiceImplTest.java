package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.config.TicketingProperties;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.dto.CreateOrderRequest;
import com.urbantransport.ticketing.dto.OrderResponse;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.PaymentRequestedPayload;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.exception.SeatsUnavailableException;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.OrderRules;
import com.urbantransport.ticketing.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDateTime NOW = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRIP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORIGIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ORDER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String NIT = "12345678";
    private static final String EMAIL = "ana@mail.com";
    private static final String KEY = "key-1";
    private static final List<Integer> REQUESTED_SEATS = List.of(3, 1);
    private static final List<Integer> SEATS = List.of(1, 3);
    private static final int MAX_PENDING = 2;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private TripSnapshotRepository tripSnapshotRepository;

    @Mock
    private OrderRules rules;

    @Mock
    private OutboxService outboxService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Captor
    private ArgumentCaptor<PurchaseOrderEntity> orderCaptor;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(purchaseOrderRepository, tripSnapshotRepository, rules, outboxService,
                transactionTemplate, new TicketingProperties("America/Guatemala", 10, Duration.ofMinutes(5),
                MAX_PENDING), CLOCK);
    }

    @Nested
    class Create {

        @Test
        void validRequest_savesPendingOrderAndRecordsPaymentRequested() {
            TripSnapshotEntity trip = trip();
            stubValidations(null, trip);
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class))).thenAnswer(savedWithId());

            OrderResponse response = orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), null);

            verify(rules).requireSellable(trip);
            verify(rules).validateSeatsInRange(trip, SEATS);
            verify(rules).assertSeatsFree(TRIP_ID, SEATS);
            verify(purchaseOrderRepository).saveAndFlush(orderCaptor.capture());
            PurchaseOrderEntity saved = orderCaptor.getValue();
            assertEquals(TRIP_ID, saved.getTripId());
            assertEquals(SalesChannel.ONLINE, saved.getChannel());
            assertEquals(CUSTOMER_ID, saved.getCustomerId());
            assertEquals(NIT, saved.getCustomerNit());
            assertEquals(EMAIL, saved.getBuyerEmail());
            assertEquals(ORIGIN_ID, saved.getBranchId());
            assertNull(saved.getSoldByUserId());
            assertEquals(new BigDecimal("100.00"), saved.getTotal());
            assertEquals(OrderStatus.PENDING, saved.getStatus());
            assertNull(saved.getIdempotencyKey());
            assertEquals(TODAY, saved.getPaymentDate());
            assertEquals(NOW.plusMinutes(5), saved.getExpiresAt());
            assertNull(saved.getConfirmedAt());
            assertEquals(2, saved.getTickets().size());
            TicketEntity first = saved.getTickets().get(0);
            assertEquals(1, first.getSeatNumber());
            assertEquals("1A", first.getSeatLabel());
            assertEquals("1C", saved.getTickets().get(1).getSeatLabel());
            assertEquals(new BigDecimal("50.00"), first.getPricePaid());
            assertEquals(TicketStatus.PENDING, first.getStatus());
            assertEquals(SalesChannel.ONLINE, first.getChannel());
            assertEquals(ORIGIN_ID, first.getBranchId());
            assertEquals(CUSTOMER_ID, first.getCustomerId());
            assertEquals(NIT, first.getCustomerNit());
            assertSame(saved, first.getOrder());
            verify(outboxService).record(EventTypes.AGGREGATE_ORDER, ORDER_ID, EventTypes.PAYMENT_REQUESTED,
                    new PaymentRequestedPayload(EventTypes.REFERENCE_ORDER, ORDER_ID, CUSTOMER_ID, NIT,
                            new BigDecimal("100.00"), TODAY));
            verify(purchaseOrderRepository, never()).findByCustomerIdAndIdempotencyKey(any(), any());
            assertEquals(ORDER_ID, response.getOrderId());
            assertEquals(OrderStatus.PENDING, response.getStatus());
            assertEquals("Central", response.getOriginName());
            assertEquals("Xela", response.getDestinationName());
            assertEquals("P-123ABC", response.getBusPlate());
            assertEquals(List.of(1, 3), response.getTickets().stream().map(t -> t.getSeatNumber()).toList());
        }

        @Test
        void newIdempotencyKey_isTrimmedAndStored() {
            stubValidations(null, trip());
            when(purchaseOrderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY))
                    .thenReturn(Optional.empty());
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class))).thenAnswer(savedWithId());

            orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), "  " + KEY + " ");

            verify(purchaseOrderRepository).saveAndFlush(orderCaptor.capture());
            assertEquals(KEY, orderCaptor.getValue().getIdempotencyKey());
        }

        @Test
        void repeatedIdempotencyKey_returnsExistingOrderWithoutCreating() {
            LocalDate paymentDate = TODAY.minusDays(1);
            when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
            when(rules.resolvePaymentDate(paymentDate)).thenReturn(paymentDate);
            runTransactionsInline();
            when(purchaseOrderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY))
                    .thenReturn(Optional.of(existingOrder(CUSTOMER_ID)));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip()));

            OrderResponse response = orderService.create(CUSTOMER_ID, NIT, EMAIL, request(paymentDate), KEY);

            assertEquals(ORDER_ID, response.getOrderId());
            assertEquals(OrderStatus.CONFIRMED, response.getStatus());
            verify(rules, never()).findTrip(any());
            verify(purchaseOrderRepository, never()).saveAndFlush(any());
            verifyNoInteractions(outboxService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"con espacio", "clave/rara", "ñandú",
                "12345678901234567890123456789012345678901234567890123456789012345"})
        void invalidIdempotencyKey_throwsBadRequest(String key) {
            assertApiError(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key inválida: use de 1 a 64 letras, números, guiones o guiones bajos",
                    () -> orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), key));

            verifyNoInteractions(rules, purchaseOrderRepository, transactionTemplate, outboxService);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void blankIdempotencyKey_isIgnored(String key) {
            stubValidations(null, trip());
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class))).thenAnswer(savedWithId());

            orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), key);

            verify(purchaseOrderRepository, never()).findByCustomerIdAndIdempotencyKey(any(), any());
            verify(purchaseOrderRepository).saveAndFlush(orderCaptor.capture());
            assertNull(orderCaptor.getValue().getIdempotencyKey());
        }

        @Test
        void tooManyPendingOrders_throwsConflict() {
            TripSnapshotEntity trip = trip();
            when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
            when(rules.resolvePaymentDate(null)).thenReturn(TODAY);
            runTransactionsInline();
            when(rules.findTrip(TRIP_ID)).thenReturn(trip);
            when(purchaseOrderRepository.countByCustomerIdAndStatus(CUSTOMER_ID, OrderStatus.PENDING))
                    .thenReturn((long) MAX_PENDING);

            assertApiError(HttpStatus.CONFLICT,
                    "Tienes demasiadas órdenes pendientes de pago (2); espera a que se completen o expiren",
                    () -> orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), null));

            verify(rules, never()).assertSeatsFree(any(), any());
            verify(purchaseOrderRepository, never()).saveAndFlush(any());
            verifyNoInteractions(outboxService);
        }

        @Test
        void invalidSeats_throwBeforeOpeningTransaction() {
            when(rules.normalizeSeats(REQUESTED_SEATS))
                    .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "Hay asientos repetidos en la petición"));

            assertApiError(HttpStatus.BAD_REQUEST, "Hay asientos repetidos en la petición",
                    () -> orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), KEY));

            verifyNoInteractions(purchaseOrderRepository, transactionTemplate, outboxService);
        }

        @Test
        void tripNotSellable_propagatesWithoutSaving() {
            TripSnapshotEntity trip = trip();
            when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
            when(rules.resolvePaymentDate(null)).thenReturn(TODAY);
            runTransactionsInline();
            when(rules.findTrip(TRIP_ID)).thenReturn(trip);
            doThrow(new ApiException(HttpStatus.CONFLICT,
                    "El viaje ya no está disponible para la venta")).when(rules).requireSellable(trip);

            assertApiError(HttpStatus.CONFLICT, "El viaje ya no está disponible para la venta",
                    () -> orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), null));

            verify(purchaseOrderRepository, never()).saveAndFlush(any());
            verifyNoInteractions(outboxService);
        }

        @Test
        void seatsTakenBeforeSaving_propagatesSeatsUnavailable() {
            stubValidations(null, trip());
            doThrow(new SeatsUnavailableException(List.of(3)))
                    .when(rules).assertSeatsFree(TRIP_ID, SEATS);

            SeatsUnavailableException exception = assertThrows(SeatsUnavailableException.class,
                    () -> orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), null));

            assertEquals(List.of(3), exception.getSeats());
            verify(purchaseOrderRepository, never()).saveAndFlush(any());
            verifyNoInteractions(outboxService);
        }

        @Test
        void integrityViolationOnSeat_throwsSeatsUnavailableWithTakenSeats() {
            stubValidations(null, trip());
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_active_seat"));
            when(rules.takenAmong(TRIP_ID, SEATS)).thenReturn(List.of(3));

            SeatsUnavailableException exception = assertThrows(SeatsUnavailableException.class,
                    () -> orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), null));

            assertEquals(HttpStatus.CONFLICT, exception.getStatus());
            assertEquals(List.of(3), exception.getSeats());
            verifyNoInteractions(outboxService);
        }

        @Test
        void integrityViolationOnSameKey_returnsOrderCreatedConcurrently() {
            stubValidations(null, trip());
            when(purchaseOrderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY))
                    .thenReturn(Optional.empty(), Optional.of(existingOrder(CUSTOMER_ID)));
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_orders_idempotency"));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip()));

            OrderResponse response = orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), KEY);

            assertEquals(ORDER_ID, response.getOrderId());
            verify(rules, never()).takenAmong(any(), any());
            verifyNoInteractions(outboxService);
        }

        @Test
        void integrityViolationWithoutTakenSeats_rethrowsSameException() {
            DataIntegrityViolationException violation = new DataIntegrityViolationException("otra restricción");
            stubValidations(null, trip());
            when(purchaseOrderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, KEY))
                    .thenReturn(Optional.empty());
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class))).thenThrow(violation);
            when(rules.takenAmong(TRIP_ID, SEATS)).thenReturn(List.of());

            DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                    () -> orderService.create(CUSTOMER_ID, NIT, EMAIL, request(null), KEY));

            assertSame(violation, thrown);
            verifyNoInteractions(outboxService);
        }
    }

    @Nested
    class GetForCustomer {

        @Test
        void ownOrder_isReturnedWithTripData() {
            when(purchaseOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existingOrder(CUSTOMER_ID)));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip()));

            OrderResponse response = orderService.getForCustomer(ORDER_ID, CUSTOMER_ID);

            assertEquals(ORDER_ID, response.getOrderId());
            assertEquals(TRIP_ID, response.getTripId());
            assertEquals("Central", response.getOriginName());
            assertEquals(new BigDecimal("50.00"), response.getTotal());
            assertEquals(List.of(2, 5), response.getTickets().stream().map(t -> t.getSeatNumber()).toList());
        }

        @Test
        void orderOfAnotherCustomer_throwsNotFound() {
            when(purchaseOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existingOrder(OTHER_CUSTOMER_ID)));

            assertApiError(HttpStatus.NOT_FOUND, "La orden no existe",
                    () -> orderService.getForCustomer(ORDER_ID, CUSTOMER_ID));

            verifyNoInteractions(tripSnapshotRepository);
        }

        @Test
        void missingOrder_throwsNotFound() {
            when(purchaseOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, "La orden no existe",
                    () -> orderService.getForCustomer(ORDER_ID, CUSTOMER_ID));
        }

        @Test
        void orderWithoutTripSnapshot_throwsIllegalState() {
            when(purchaseOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existingOrder(CUSTOMER_ID)));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> orderService.getForCustomer(ORDER_ID, CUSTOMER_ID));

            assertEquals("Orden " + ORDER_ID + " sin viaje", exception.getMessage());
        }
    }

    private void stubValidations(LocalDate paymentDate, TripSnapshotEntity trip) {
        when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
        when(rules.resolvePaymentDate(paymentDate)).thenReturn(paymentDate == null ? TODAY : paymentDate);
        runTransactionsInline();
        when(rules.findTrip(TRIP_ID)).thenReturn(trip);
        when(purchaseOrderRepository.countByCustomerIdAndStatus(CUSTOMER_ID, OrderStatus.PENDING)).thenReturn(1L);
    }

    private void runTransactionsInline() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private static Answer<PurchaseOrderEntity> savedWithId() {
        return inv -> {
            PurchaseOrderEntity order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return order;
        };
    }

    private static CreateOrderRequest request(LocalDate paymentDate) {
        return new CreateOrderRequest(TRIP_ID, REQUESTED_SEATS, paymentDate);
    }

    private static TripSnapshotEntity trip() {
        return TripSnapshotEntity.builder()
                .tripId(TRIP_ID)
                .originBranchId(ORIGIN_ID)
                .originName("Central")
                .destinationName("Xela")
                .busPlate("P-123ABC")
                .seatRows(10)
                .seatColumns(4)
                .aisleAfterColumn(2)
                .capacity(40)
                .ticketPrice(new BigDecimal("50"))
                .departureAt(NOW.plusDays(1))
                .status(TripStatus.SCHEDULED)
                .build();
    }

    private static PurchaseOrderEntity existingOrder(UUID customerId) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .id(ORDER_ID)
                .tripId(TRIP_ID)
                .channel(SalesChannel.ONLINE)
                .customerId(customerId)
                .customerNit(NIT)
                .total(new BigDecimal("50.00"))
                .status(OrderStatus.CONFIRMED)
                .paymentDate(TODAY)
                .build();
        order.addTicket(TicketEntity.builder().seatNumber(5).seatLabel("2A").status(TicketStatus.CONFIRMED).build());
        order.addTicket(TicketEntity.builder().seatNumber(2).seatLabel("1B").status(TicketStatus.CONFIRMED).build());
        return order;
    }

    private static void assertApiError(HttpStatus status, String message, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(message, exception.getMessage());
    }
}
