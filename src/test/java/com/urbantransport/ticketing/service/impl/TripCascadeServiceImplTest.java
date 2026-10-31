package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.RefundRequestedPayload;
import com.urbantransport.ticketing.event.TicketCancelledPayload;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripCascadeServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDateTime NOW = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final UUID TRIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final BigDecimal PRICE = new BigDecimal("60.00");

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private TripSnapshotRepository tripSnapshotRepository;

    @Mock
    private OutboxService outboxService;

    private TripCascadeServiceImpl tripCascadeService;

    @BeforeEach
    void setUp() {
        tripCascadeService = new TripCascadeServiceImpl(purchaseOrderRepository, tripSnapshotRepository,
                outboxService, CLOCK);
    }

    @Nested
    class ExpirePendingOrders {

        @Test
        void pendingOrders_areExpiredAndReleaseSeats() {
            PurchaseOrderEntity first = order(1, SalesChannel.ONLINE, OrderStatus.PENDING, TicketStatus.PENDING);
            PurchaseOrderEntity second = order(2, SalesChannel.ONLINE, OrderStatus.PENDING, TicketStatus.PENDING,
                    TicketStatus.PENDING);
            when(purchaseOrderRepository.findByTripIdAndStatusInForUpdate(TRIP_ID, List.of(OrderStatus.PENDING)))
                    .thenReturn(List.of(first, second));

            tripCascadeService.expirePendingOrders(TRIP_ID);

            for (PurchaseOrderEntity order : List.of(first, second)) {
                assertEquals(OrderStatus.EXPIRED, order.getStatus());
                assertEquals(CancelReason.TRIP_STARTED, order.getFailureReason());
                order.getTickets().forEach(ticket -> {
                    assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
                    assertEquals(CancelReason.TRIP_STARTED, ticket.getCancelReason());
                    assertEquals(NOW, ticket.getCancelledAt());
                });
            }
            verifyNoInteractions(outboxService, tripSnapshotRepository);
        }

        @Test
        void noPendingOrders_changesNothing() {
            when(purchaseOrderRepository.findByTripIdAndStatusInForUpdate(TRIP_ID, List.of(OrderStatus.PENDING)))
                    .thenReturn(List.of());

            tripCascadeService.expirePendingOrders(TRIP_ID);

            verifyNoInteractions(outboxService, tripSnapshotRepository);
        }
    }

    @Nested
    class CancelTripOrders {

        @Test
        void confirmedOnlineTickets_areCancelledAnnouncedAndRefundedToWallet() {
            TripSnapshotEntity trip = trip();
            PurchaseOrderEntity order = order(1, SalesChannel.ONLINE, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED,
                    TicketStatus.CONFIRMED);
            stubOrders(trip, order);

            tripCascadeService.cancelTripOrders(TRIP_ID);

            assertEquals(OrderStatus.CANCELLED, order.getStatus());
            assertEquals(CancelReason.TRIP_DELETED, order.getFailureReason());
            for (TicketEntity ticket : order.getTickets()) {
                assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
                assertEquals(CancelReason.TRIP_DELETED, ticket.getCancelReason());
                assertEquals(NOW, ticket.getCancelledAt());
                verify(outboxService).record(EventTypes.AGGREGATE_TICKET, ticket.getId(), EventTypes.TICKET_CANCELLED,
                        TicketCancelledPayload.of(order, ticket, trip, CancelReason.TRIP_DELETED,
                                TicketCancelledPayload.REFUND_WALLET));
                verify(outboxService).record(EventTypes.AGGREGATE_TICKET, ticket.getId(), EventTypes.REFUND_REQUESTED,
                        new RefundRequestedPayload(ticket.getId(), CUSTOMER_ID, "12345678", PRICE, order.getId(),
                                ticket.getSeatLabel()));
            }
        }

        @Test
        void counterTickets_areAnnouncedForCashRefundWithoutWalletRefund() {
            TripSnapshotEntity trip = trip();
            PurchaseOrderEntity order = order(1, SalesChannel.COUNTER, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED);
            stubOrders(trip, order);

            tripCascadeService.cancelTripOrders(TRIP_ID);

            TicketEntity ticket = order.getTickets().get(0);
            assertEquals(OrderStatus.CANCELLED, order.getStatus());
            assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
            verify(outboxService).record(EventTypes.AGGREGATE_TICKET, ticket.getId(), EventTypes.TICKET_CANCELLED,
                    TicketCancelledPayload.of(order, ticket, trip, CancelReason.TRIP_DELETED,
                            TicketCancelledPayload.REFUND_CASH));
            verify(outboxService, never()).record(any(), any(), eq(EventTypes.REFUND_REQUESTED), any());
        }

        @Test
        void pendingOrder_isCancelledWithoutAnnouncingOrRefunding() {
            PurchaseOrderEntity order = order(1, SalesChannel.ONLINE, OrderStatus.PENDING, TicketStatus.PENDING);
            stubOrders(trip(), order);

            tripCascadeService.cancelTripOrders(TRIP_ID);

            assertEquals(OrderStatus.CANCELLED, order.getStatus());
            assertEquals(TicketStatus.CANCELLED, order.getTickets().get(0).getStatus());
            verifyNoInteractions(outboxService);
        }

        @Test
        void mixedOrders_announceOnlyChargedTickets() {
            PurchaseOrderEntity pending = order(1, SalesChannel.ONLINE, OrderStatus.PENDING, TicketStatus.PENDING);
            PurchaseOrderEntity online = order(2, SalesChannel.ONLINE, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED);
            PurchaseOrderEntity counter = order(3, SalesChannel.COUNTER, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED,
                    TicketStatus.CANCELLED);
            stubOrders(trip(), pending, online, counter);

            tripCascadeService.cancelTripOrders(TRIP_ID);

            verify(outboxService, times(2)).record(any(), any(), eq(EventTypes.TICKET_CANCELLED), any());
            verify(outboxService, times(1)).record(any(), any(), eq(EventTypes.REFUND_REQUESTED), any());
            assertNull(counter.getTickets().get(1).getCancelReason());
        }

        @Test
        void noActiveOrders_recordsNothing() {
            stubOrders(trip());

            tripCascadeService.cancelTripOrders(TRIP_ID);

            verifyNoInteractions(outboxService);
        }

        @Test
        void tripWithoutSnapshot_throwsWithoutTouchingOrders() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> tripCascadeService.cancelTripOrders(TRIP_ID));

            assertEquals("Viaje " + TRIP_ID + " sin snapshot", exception.getMessage());
            verifyNoInteractions(purchaseOrderRepository, outboxService);
        }
    }

    private void stubOrders(TripSnapshotEntity trip, PurchaseOrderEntity... orders) {
        when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(purchaseOrderRepository.findByTripIdAndStatusInForUpdate(TRIP_ID,
                List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED))).thenReturn(List.of(orders));
    }

    private static PurchaseOrderEntity order(int number, SalesChannel channel, OrderStatus status,
                                             TicketStatus... ticketStatuses) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-00000000000" + number))
                .tripId(TRIP_ID)
                .channel(channel)
                .branchId(BRANCH_ID)
                .customerId(CUSTOMER_ID)
                .customerNit("12345678")
                .buyerEmail(channel == SalesChannel.ONLINE ? "ana@mail.com" : null)
                .total(PRICE)
                .status(status)
                .paymentDate(LocalDate.now(ZONE))
                .build();
        for (int i = 0; i < ticketStatuses.length; i++) {
            order.addTicket(TicketEntity.builder()
                    .id(UUID.fromString("00000000-0000-0000-0000-0000000001" + number + i))
                    .tripId(TRIP_ID)
                    .seatNumber(number * 10 + i)
                    .seatLabel("S" + number + i)
                    .pricePaid(PRICE)
                    .status(ticketStatuses[i])
                    .build());
        }
        return order;
    }

    private static TripSnapshotEntity trip() {
        return TripSnapshotEntity.builder()
                .tripId(TRIP_ID)
                .originName("Central")
                .destinationName("Xela")
                .departureAt(NOW.plusDays(1))
                .status(TripStatus.DELETED)
                .build();
    }
}
