package com.urbantransport.ticketing.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PurchaseOrderEntityTest {

    private static final LocalDateTime NOW = LocalDateTime.now(ZoneId.of("America/Guatemala"));
    private static final String REASON = CancelReason.TRIP_DELETED;

    @Test
    void addTicket_linksTicketToOrder() {
        PurchaseOrderEntity order = new PurchaseOrderEntity();
        TicketEntity ticket = ticket(1, TicketStatus.PENDING);

        order.addTicket(ticket);

        assertSame(order, ticket.getOrder());
        assertEquals(List.of(ticket), order.getTickets());
    }

    @Test
    void confirmPendingTickets_onlyTouchesPending() {
        PurchaseOrderEntity order = order(TicketStatus.PENDING, TicketStatus.CANCELLED);

        order.confirmPendingTickets();

        assertEquals(TicketStatus.CONFIRMED, order.getTickets().get(0).getStatus());
        assertEquals(TicketStatus.CANCELLED, order.getTickets().get(1).getStatus());
    }

    @Test
    void cancelPendingTickets_cancelsOnlyPendingWithReasonAndDate() {
        PurchaseOrderEntity order = order(TicketStatus.PENDING, TicketStatus.CONFIRMED);

        order.cancelPendingTickets(REASON, NOW);

        TicketEntity pending = order.getTickets().get(0);
        TicketEntity confirmed = order.getTickets().get(1);
        assertEquals(TicketStatus.CANCELLED, pending.getStatus());
        assertEquals(REASON, pending.getCancelReason());
        assertEquals(NOW, pending.getCancelledAt());
        assertEquals(TicketStatus.CONFIRMED, confirmed.getStatus());
        assertNull(confirmed.getCancelReason());
        assertNull(confirmed.getCancelledAt());
    }

    @Test
    void cancelActiveTickets_cancelsActiveAndReturnsOnlyCharged() {
        PurchaseOrderEntity order = order(TicketStatus.PENDING, TicketStatus.CONFIRMED, TicketStatus.REFUNDED,
                TicketStatus.CANCELLED);
        TicketEntity confirmed = order.getTickets().get(1);

        List<TicketEntity> charged = order.cancelActiveTickets(REASON, NOW);

        assertEquals(List.of(confirmed), charged);
        assertEquals(TicketStatus.CANCELLED, order.getTickets().get(0).getStatus());
        assertEquals(TicketStatus.CANCELLED, confirmed.getStatus());
        assertEquals(REASON, confirmed.getCancelReason());
        assertEquals(NOW, confirmed.getCancelledAt());
        assertEquals(TicketStatus.REFUNDED, order.getTickets().get(2).getStatus());
        assertNull(order.getTickets().get(2).getCancelReason());
        assertNull(order.getTickets().get(3).getCancelReason());
    }

    private static PurchaseOrderEntity order(TicketStatus... statuses) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder().build();
        for (int i = 0; i < statuses.length; i++) {
            order.addTicket(ticket(i + 1, statuses[i]));
        }
        return order;
    }

    private static TicketEntity ticket(int seat, TicketStatus status) {
        return TicketEntity.builder().seatNumber(seat).status(status).build();
    }
}
