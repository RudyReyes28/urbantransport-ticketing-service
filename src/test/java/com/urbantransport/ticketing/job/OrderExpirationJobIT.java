package com.urbantransport.ticketing.job;

import com.urbantransport.ticketing.AbstractIntegrationTest;
import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.service.OrderExpirationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderExpirationJobIT extends AbstractIntegrationTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private OrderExpirationJob orderExpirationJob;

    @Autowired
    private OrderExpirationService orderExpirationService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void run_expiresOnlyOverduePendingOrdersAndFreesTheirSeats() throws Exception {
        TripSnapshotEntity trip = saveTrip(UUID.randomUUID(), UUID.randomUUID(), now().plusDays(1),
                TripStatus.SCHEDULED);
        PurchaseOrderEntity overdue = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING,
                now().minusMinutes(1), 1, 2);
        PurchaseOrderEntity onTime = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING,
                now().plusMinutes(5), 3);
        PurchaseOrderEntity paid = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED,
                now().minusMinutes(10), 4);

        orderExpirationJob.run();

        PurchaseOrderEntity expired = load(overdue.getId());
        assertEquals(OrderStatus.EXPIRED, expired.getStatus());
        for (TicketEntity ticket : expired.getTickets()) {
            assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
            assertEquals(CancelReason.ORDER_EXPIRED, ticket.getCancelReason());
            assertNotNull(ticket.getCancelledAt());
        }
        assertEquals(OrderStatus.PENDING, load(onTime.getId()).getStatus());
        assertEquals(OrderStatus.CONFIRMED, load(paid.getId()).getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_events"));

        mockMvc.perform(get("/trips/{id}/seats", trip.getTripId()))
                .andExpect(jsonPath("$.seats[0].status").value("FREE"))
                .andExpect(jsonPath("$.seats[2].status").value("OCCUPIED"));
        mockMvc.perform(post("/orders").with(customer(UUID.randomUUID(), "87654321"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":\"%s\",\"seats\":[1,2]}".formatted(trip.getTripId())))
                .andExpect(status().isAccepted());
    }

    @Test
    void expireOverdueOrders_returnsHowManyWereExpired() {
        TripSnapshotEntity trip = saveTrip(UUID.randomUUID(), UUID.randomUUID(), now().plusDays(1),
                TripStatus.SCHEDULED);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().minusMinutes(2), 1);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().minusMinutes(1), 2);

        assertEquals(2, orderExpirationService.expireOverdueOrders());
        assertEquals(0, orderExpirationService.expireOverdueOrders());
    }

    private PurchaseOrderEntity load(UUID orderId) {
        return transactionTemplate.execute(status -> {
            PurchaseOrderEntity order = purchaseOrderRepository.findById(orderId).orElseThrow();
            order.getTickets().size();
            return order;
        });
    }
}
