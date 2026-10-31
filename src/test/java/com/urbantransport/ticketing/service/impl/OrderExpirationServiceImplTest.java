package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpirationServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDateTime NOW = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final UUID FIRST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID THIRD_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private OrderExpirationServiceImpl orderExpirationService;

    @BeforeEach
    void setUp() {
        orderExpirationService = new OrderExpirationServiceImpl(purchaseOrderRepository, transactionTemplate, CLOCK);
    }

    @Test
    void overduePendingOrders_areExpiredAndReleaseSeats() {
        PurchaseOrderEntity first = order(OrderStatus.PENDING, NOW.minusMinutes(1));
        PurchaseOrderEntity second = order(OrderStatus.PENDING, NOW.minusSeconds(1));
        stubIds(FIRST_ID, SECOND_ID);
        runTransactionsInline();
        when(purchaseOrderRepository.findByIdForUpdate(FIRST_ID)).thenReturn(Optional.of(first));
        when(purchaseOrderRepository.findByIdForUpdate(SECOND_ID)).thenReturn(Optional.of(second));

        int expired = orderExpirationService.expireOverdueOrders();

        assertEquals(2, expired);
        for (PurchaseOrderEntity order : List.of(first, second)) {
            assertEquals(OrderStatus.EXPIRED, order.getStatus());
            assertNull(order.getFailureReason());
            TicketEntity ticket = order.getTickets().get(0);
            assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
            assertEquals(CancelReason.ORDER_EXPIRED, ticket.getCancelReason());
            assertEquals(NOW, ticket.getCancelledAt());
        }
    }

    @Test
    void noOverdueOrders_returnsZeroWithoutTransactions() {
        stubIds();

        assertEquals(0, orderExpirationService.expireOverdueOrders());

        verifyNoInteractions(transactionTemplate);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void orderNoLongerPending_isNotTouched(OrderStatus status) {
        PurchaseOrderEntity order = order(status, NOW.minusMinutes(1));
        stubIds(FIRST_ID);
        runTransactionsInline();
        when(purchaseOrderRepository.findByIdForUpdate(FIRST_ID)).thenReturn(Optional.of(order));

        assertEquals(0, orderExpirationService.expireOverdueOrders());

        assertEquals(status, order.getStatus());
        assertEquals(TicketStatus.PENDING, order.getTickets().get(0).getStatus());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void orderNotYetOverdue_isNotTouched(long minutesAhead) {
        PurchaseOrderEntity order = order(OrderStatus.PENDING, NOW.plusMinutes(minutesAhead));
        stubIds(FIRST_ID);
        runTransactionsInline();
        when(purchaseOrderRepository.findByIdForUpdate(FIRST_ID)).thenReturn(Optional.of(order));

        assertEquals(0, orderExpirationService.expireOverdueOrders());

        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    @Test
    void orderWithoutExpiration_isNotTouched() {
        PurchaseOrderEntity order = order(OrderStatus.PENDING, null);
        stubIds(FIRST_ID);
        runTransactionsInline();
        when(purchaseOrderRepository.findByIdForUpdate(FIRST_ID)).thenReturn(Optional.of(order));

        assertEquals(0, orderExpirationService.expireOverdueOrders());

        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    @Test
    void vanishedOrder_isSkipped() {
        stubIds(FIRST_ID);
        runTransactionsInline();
        when(purchaseOrderRepository.findByIdForUpdate(FIRST_ID)).thenReturn(Optional.empty());

        assertEquals(0, orderExpirationService.expireOverdueOrders());
    }

    @Test
    void failingOrder_doesNotStopTheOthers() {
        PurchaseOrderEntity first = order(OrderStatus.PENDING, NOW.minusMinutes(1));
        PurchaseOrderEntity third = order(OrderStatus.PENDING, NOW.minusMinutes(1));
        stubIds(FIRST_ID, SECOND_ID, THIRD_ID);
        runTransactionsInline();
        when(purchaseOrderRepository.findByIdForUpdate(FIRST_ID)).thenReturn(Optional.of(first));
        when(purchaseOrderRepository.findByIdForUpdate(SECOND_ID)).thenThrow(new IllegalStateException("bloqueo"));
        when(purchaseOrderRepository.findByIdForUpdate(THIRD_ID)).thenReturn(Optional.of(third));

        assertEquals(2, orderExpirationService.expireOverdueOrders());

        assertEquals(OrderStatus.EXPIRED, first.getStatus());
        assertEquals(OrderStatus.EXPIRED, third.getStatus());
    }

    private void stubIds(UUID... ids) {
        when(purchaseOrderRepository.findExpiredPendingIds(OrderStatus.PENDING, NOW, PageRequest.of(0, 100)))
                .thenReturn(List.of(ids));
    }

    private void runTransactionsInline() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private static PurchaseOrderEntity order(OrderStatus status, LocalDateTime expiresAt) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder().status(status).expiresAt(expiresAt).build();
        order.addTicket(TicketEntity.builder().seatNumber(1).status(TicketStatus.PENDING).build());
        return order;
    }
}
