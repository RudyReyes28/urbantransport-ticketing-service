package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.ProcessedEventEntity;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.PaymentResultEvent.PaymentResultPayload;
import com.urbantransport.ticketing.event.RefundCompletedEvent.RefundCompletedPayload;
import com.urbantransport.ticketing.event.RefundRequestedPayload;
import com.urbantransport.ticketing.event.TicketPurchasedPayload;
import com.urbantransport.ticketing.repository.ProcessedEventRepository;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDateTime NOW = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRIP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CUSTOMER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TICKET_1 = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID TICKET_2 = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID TICKET_3 = UUID.fromString("55555555-5555-5555-5555-555555555553");
    private static final String NIT = "12345678";
    private static final BigDecimal PRICE = new BigDecimal("50.00");
    private static final PaymentResultPayload COMPLETED = new PaymentResultPayload("ORDER", ORDER_ID, null);

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private TripSnapshotRepository tripSnapshotRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private TicketRepository ticketRepository;

    @Captor
    private ArgumentCaptor<ProcessedEventEntity> processedCaptor;

    private OrderPaymentServiceImpl orderPaymentService;

    @BeforeEach
    void setUp() {
        orderPaymentService = new OrderPaymentServiceImpl(purchaseOrderRepository, tripSnapshotRepository,
                processedEventRepository, outboxService, CLOCK, ticketRepository);
    }

    @Nested
    class HandlePaymentCompleted {

        @Test
        void pendingOrder_isConfirmedAndRecordsTicketPurchased() {
            PurchaseOrderEntity order = order(OrderStatus.PENDING, TicketStatus.PENDING, TicketStatus.PENDING);
            TripSnapshotEntity trip = trip();
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));

            orderPaymentService.handlePaymentCompleted(EVENT_ID, COMPLETED);

            assertEquals(OrderStatus.CONFIRMED, order.getStatus());
            assertEquals(NOW, order.getConfirmedAt());
            order.getTickets().forEach(ticket -> assertEquals(TicketStatus.CONFIRMED, ticket.getStatus()));
            verify(outboxService).record(EventTypes.AGGREGATE_ORDER, ORDER_ID, EventTypes.TICKET_PURCHASED,
                    TicketPurchasedPayload.fromOrder(order, trip));
            assertProcessed();
        }

        @Test
        void alreadyConfirmedOrder_isLeftAsIsAndMarkedProcessed() {
            PurchaseOrderEntity order = order(OrderStatus.CONFIRMED, TicketStatus.CONFIRMED);
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

            orderPaymentService.handlePaymentCompleted(EVENT_ID, COMPLETED);

            assertEquals(OrderStatus.CONFIRMED, order.getStatus());
            assertNull(order.getConfirmedAt());
            verifyNoInteractions(outboxService, tripSnapshotRepository);
            assertProcessed();
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"FAILED", "EXPIRED", "CANCELLED"})
        void paymentOnClosedOrder_requestsRefundOfCancelledTickets(OrderStatus status) {
            PurchaseOrderEntity order = order(status, TicketStatus.CANCELLED, TicketStatus.REFUNDED,
                    TicketStatus.CANCELLED);
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

            orderPaymentService.handlePaymentCompleted(EVENT_ID, COMPLETED);

            assertEquals(status, order.getStatus());
            verify(outboxService).record(EventTypes.AGGREGATE_TICKET, TICKET_1, EventTypes.REFUND_REQUESTED,
                    new RefundRequestedPayload(TICKET_1, CUSTOMER_ID, NIT, PRICE, ORDER_ID, "1A"));
            verify(outboxService).record(EventTypes.AGGREGATE_TICKET, TICKET_3, EventTypes.REFUND_REQUESTED,
                    new RefundRequestedPayload(TICKET_3, CUSTOMER_ID, NIT, PRICE, ORDER_ID, "1C"));
            verify(outboxService, never()).record(any(), eq(TICKET_2), any(), any());
            verifyNoInteractions(tripSnapshotRepository);
            assertProcessed();
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            orderPaymentService.handlePaymentCompleted(EVENT_ID, COMPLETED);

            verifyNoInteractions(purchaseOrderRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void paymentOfAnotherReferenceType_isIgnoredWithoutMarking() {
            orderPaymentService.handlePaymentCompleted(EVENT_ID, new PaymentResultPayload("RENTAL", ORDER_ID, null));

            verifyNoInteractions(purchaseOrderRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.ticketing.service.impl.OrderPaymentServiceImplTest#incompletePayloads")
        void incompletePayload_throwsSoItGoesToDlt(PaymentResultPayload payload) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> orderPaymentService.handlePaymentCompleted(EVENT_ID, payload));

            assertEquals("Resultado de pago sin referenceType o referenceId", exception.getMessage());
            verifyNoInteractions(purchaseOrderRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void unknownOrder_throwsWithoutMarking() {
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> orderPaymentService.handlePaymentCompleted(EVENT_ID, COMPLETED));

            assertEquals("Resultado de pago de una orden inexistente: " + ORDER_ID, exception.getMessage());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void pendingOrderWithoutTrip_throwsWithoutRecordingEvent() {
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID))
                    .thenReturn(Optional.of(order(OrderStatus.PENDING, TicketStatus.PENDING)));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class,
                    () -> orderPaymentService.handlePaymentCompleted(EVENT_ID, COMPLETED));

            verifyNoInteractions(outboxService);
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    class HandlePaymentFailed {

        @Test
        void pendingOrder_failsAndReleasesSeats() {
            PurchaseOrderEntity order = order(OrderStatus.PENDING, TicketStatus.PENDING, TicketStatus.PENDING);
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

            orderPaymentService.handlePaymentFailed(EVENT_ID, failed("INSUFFICIENT_FUNDS"));

            assertEquals(OrderStatus.FAILED, order.getStatus());
            assertEquals("INSUFFICIENT_FUNDS", order.getFailureReason());
            order.getTickets().forEach(ticket -> {
                assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
                assertEquals(CancelReason.PAYMENT_FAILED, ticket.getCancelReason());
                assertEquals(NOW, ticket.getCancelledAt());
            });
            verifyNoInteractions(outboxService);
            assertProcessed();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void missingReason_isStoredAsUnknown(String reason) {
            PurchaseOrderEntity order = order(OrderStatus.PENDING, TicketStatus.PENDING);
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

            orderPaymentService.handlePaymentFailed(EVENT_ID, failed(reason));

            assertEquals("UNKNOWN", order.getFailureReason());
        }

        @Test
        void longReason_isTruncatedTo40Characters() {
            PurchaseOrderEntity order = order(OrderStatus.PENDING, TicketStatus.PENDING);
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

            orderPaymentService.handlePaymentFailed(EVENT_ID, failed("X".repeat(41)));

            assertEquals("X".repeat(40), order.getFailureReason());
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        void orderNotPending_isLeftAsIsAndMarkedProcessed(OrderStatus status) {
            PurchaseOrderEntity order = order(status, TicketStatus.CONFIRMED);
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

            orderPaymentService.handlePaymentFailed(EVENT_ID, failed("INSUFFICIENT_FUNDS"));

            assertEquals(status, order.getStatus());
            assertNull(order.getFailureReason());
            assertEquals(TicketStatus.CONFIRMED, order.getTickets().get(0).getStatus());
            assertProcessed();
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            orderPaymentService.handlePaymentFailed(EVENT_ID, failed("INSUFFICIENT_FUNDS"));

            verifyNoInteractions(purchaseOrderRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void failureOfAnotherReferenceType_isIgnoredWithoutMarking() {
            orderPaymentService.handlePaymentFailed(EVENT_ID,
                    new PaymentResultPayload("RENTAL", ORDER_ID, "INSUFFICIENT_FUNDS"));

            verifyNoInteractions(purchaseOrderRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.ticketing.service.impl.OrderPaymentServiceImplTest#incompletePayloads")
        void incompletePayload_throwsSoItGoesToDlt(PaymentResultPayload payload) {
            assertThrows(IllegalArgumentException.class,
                    () -> orderPaymentService.handlePaymentFailed(EVENT_ID, payload));

            verifyNoInteractions(purchaseOrderRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void unknownOrder_throwsWithoutMarking() {
            when(purchaseOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class,
                    () -> orderPaymentService.handlePaymentFailed(EVENT_ID, failed("INSUFFICIENT_FUNDS")));

            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    class HandleRefundCompleted {

        @Test
        void cancelledTicket_isMarkedRefunded() {
            TicketEntity ticket = ticket(TICKET_1, 1, TicketStatus.CANCELLED);
            when(ticketRepository.findById(TICKET_1)).thenReturn(Optional.of(ticket));

            orderPaymentService.handleRefundCompleted(EVENT_ID, new RefundCompletedPayload(TICKET_1, PRICE));

            assertEquals(TicketStatus.REFUNDED, ticket.getStatus());
            assertProcessed();
        }

        @ParameterizedTest
        @EnumSource(value = TicketStatus.class, names = "CANCELLED", mode = EnumSource.Mode.EXCLUDE)
        void ticketNotCancelled_isLeftAsIsAndMarkedProcessed(TicketStatus status) {
            TicketEntity ticket = ticket(TICKET_1, 1, status);
            when(ticketRepository.findById(TICKET_1)).thenReturn(Optional.of(ticket));

            orderPaymentService.handleRefundCompleted(EVENT_ID, new RefundCompletedPayload(TICKET_1, PRICE));

            assertEquals(status, ticket.getStatus());
            assertProcessed();
        }

        @Test
        void refundOfSomethingElse_isIgnoredWithoutMarking() {
            when(ticketRepository.findById(TICKET_1)).thenReturn(Optional.empty());

            orderPaymentService.handleRefundCompleted(EVENT_ID, new RefundCompletedPayload(TICKET_1, PRICE));

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            orderPaymentService.handleRefundCompleted(EVENT_ID, new RefundCompletedPayload(TICKET_1, PRICE));

            verifyNoInteractions(ticketRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void missingPayload_throwsSoItGoesToDlt() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> orderPaymentService.handleRefundCompleted(EVENT_ID, null));

            assertEquals("RefundCompleted sin ticketId", exception.getMessage());
            verifyNoInteractions(ticketRepository);
        }

        @Test
        void missingTicketId_throwsSoItGoesToDlt() {
            assertThrows(IllegalArgumentException.class,
                    () -> orderPaymentService.handleRefundCompleted(EVENT_ID, new RefundCompletedPayload(null, PRICE)));

            verifyNoInteractions(ticketRepository);
            verify(processedEventRepository, never()).save(any());
        }
    }

    static Stream<PaymentResultPayload> incompletePayloads() {
        return Stream.of(null,
                new PaymentResultPayload(null, ORDER_ID, null),
                new PaymentResultPayload("ORDER", null, null));
    }

    private void assertProcessed() {
        verify(processedEventRepository).save(processedCaptor.capture());
        assertEquals(EVENT_ID, processedCaptor.getValue().getEventId());
    }

    private static PaymentResultPayload failed(String reason) {
        return new PaymentResultPayload("ORDER", ORDER_ID, reason);
    }

    private static PurchaseOrderEntity order(OrderStatus status, TicketStatus... ticketStatuses) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .id(ORDER_ID)
                .tripId(TRIP_ID)
                .channel(SalesChannel.ONLINE)
                .customerId(CUSTOMER_ID)
                .customerNit(NIT)
                .buyerEmail("ana@mail.com")
                .total(PRICE.multiply(BigDecimal.valueOf(ticketStatuses.length)))
                .status(status)
                .paymentDate(LocalDate.now(ZONE))
                .build();
        UUID[] ids = {TICKET_1, TICKET_2, TICKET_3};
        for (int i = 0; i < ticketStatuses.length; i++) {
            order.addTicket(ticket(ids[i], i + 1, ticketStatuses[i]));
        }
        return order;
    }

    private static TicketEntity ticket(UUID id, int seat, TicketStatus status) {
        return TicketEntity.builder()
                .id(id)
                .tripId(TRIP_ID)
                .seatNumber(seat)
                .seatLabel("1" + (char) ('A' + seat - 1))
                .pricePaid(PRICE)
                .status(status)
                .build();
    }

    private static TripSnapshotEntity trip() {
        return TripSnapshotEntity.builder()
                .tripId(TRIP_ID)
                .originName("Central")
                .destinationName("Xela")
                .busPlate("P-123ABC")
                .departureAt(NOW.plusDays(1))
                .status(TripStatus.SCHEDULED)
                .build();
    }
}
