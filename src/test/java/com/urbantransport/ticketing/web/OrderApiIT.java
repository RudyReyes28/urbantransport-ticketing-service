package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.AbstractIntegrationTest;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderApiIT extends AbstractIntegrationTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORIGIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DESTINATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private TransactionTemplate transactionTemplate;

    private TripSnapshotEntity trip;

    @BeforeEach
    void seedTrip() {
        trip = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.SCHEDULED);
    }

    @Test
    void create_persistsPendingOrderAndRecordsPaymentRequested() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "1234567-8"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[7, 1]", null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.channel").value("ONLINE"))
                .andExpect(jsonPath("$.customerNit").value("12345678"))
                .andExpect(jsonPath("$.total").value(100.0))
                .andExpect(jsonPath("$.originName").value("Central"))
                .andExpect(jsonPath("$.tickets[0].seatNumber").value(1))
                .andExpect(jsonPath("$.tickets[0].seatLabel").value("1A"))
                .andExpect(jsonPath("$.tickets[1].seatLabel").value("2C"))
                .andExpect(jsonPath("$.tickets[1].status").value("PENDING"))
                .andReturn();

        UUID orderId = idAt(result, "$.orderId");
        PurchaseOrderEntity order = loadOrder(orderId);
        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertEquals(SalesChannel.ONLINE, order.getChannel());
        assertEquals(CUSTOMER_ID, order.getCustomerId());
        assertEquals("cliente@mail.com", order.getBuyerEmail());
        assertEquals(ORIGIN_ID, order.getBranchId());
        assertNull(order.getSoldByUserId());
        assertEquals(0, new BigDecimal("100.00").compareTo(order.getTotal()));
        assertEquals(today(), order.getPaymentDate());
        assertTrue(order.getExpiresAt().isAfter(now().plusMinutes(4)));
        assertEquals(List.of(1, 7), order.getTickets().stream().map(TicketEntity::getSeatNumber).sorted().toList());
        assertTrue(order.getTickets().stream().allMatch(ticket -> ticket.getStatus() == TicketStatus.PENDING));
        assertEquals(List.of("PaymentRequested"), outboxEventTypes(orderId));
        verifyNoInteractions(customerClient);
    }

    @Test
    void create_withSameIdempotencyKey_createsOnlyOneOrder() throws Exception {
        MvcResult first = mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .header("Idempotency-Key", "compra-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[1]", null)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID orderId = idAt(first, "$.orderId");

        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .header("Idempotency-Key", "compra-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[2]", null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.tickets[0].seatNumber").value(1));

        assertEquals(1, purchaseOrderRepository.count());
        assertEquals(List.of("PaymentRequested"), outboxEventTypes(orderId));
    }

    @Test
    void create_withInvalidIdempotencyKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .header("Idempotency-Key", "clave con espacios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[1]", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")));

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_withInvalidBody_returnsValidationError() throws Exception {
        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("tripId")))
                .andExpect(jsonPath("$.message", containsString("seats")))
                .andExpect(jsonPath("$.path").value("/orders"));

        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[0, -2]", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("seats")));

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_withMalformedJson_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Cuerpo")));
    }

    @Test
    void create_withRepeatedSeats_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[3, 3]", null)))
                .andExpect(status().isBadRequest());

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_withSeatOutOfRange_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[40, 41]", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("[41]")));

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_withFuturePaymentDate_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[1]", today().plusDays(1).toString())))
                .andExpect(status().isBadRequest());

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_withPastPaymentDate_keepsIt() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[1]", today().minusDays(2).toString())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentDate").value(today().minusDays(2).toString()))
                .andReturn();

        assertEquals(today().minusDays(2), loadOrder(idAt(result, "$.orderId")).getPaymentDate());
    }

    @Test
    void create_withTakenSeat_returnsConflictListingIt() throws Exception {
        saveOnlineOrder(trip, OTHER_CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 3);
        saveCounterOrder(trip, OTHER_CUSTOMER_ID, "CF", 5);

        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[5, 4, 3]", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeats[0]").value(3))
                .andExpect(jsonPath("$.unavailableSeats[1]").value(5))
                .andExpect(jsonPath("$.path").value("/orders"));

        assertEquals(2, purchaseOrderRepository.count());
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_events"));
    }

    @Test
    void create_onSeatOfCancelledTicket_succeeds() throws Exception {
        saveOnlineOrder(trip, OTHER_CUSTOMER_ID, OrderStatus.EXPIRED, TicketStatus.CANCELLED, now().minusMinutes(1), 3);

        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[3]", null)))
                .andExpect(status().isAccepted());

        assertEquals(2, count("SELECT COUNT(*) FROM tickets WHERE trip_id = ? AND seat_number = 3",
                trip.getTripId()));
    }

    @Test
    void create_withTooManyPendingOrders_returnsConflict() throws Exception {
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 1);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 2);

        mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[3]", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("(2)")));

        assertEquals(2, purchaseOrderRepository.count());
    }

    @Test
    void create_forUnknownOrDeletedTrip_returnsNotFound() throws Exception {
        TripSnapshotEntity deleted = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.DELETED);

        for (UUID tripId : List.of(UUID.randomUUID(), deleted.getTripId())) {
            mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderJson(tripId, "[1]", null)))
                    .andExpect(status().isNotFound());
        }

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_forDepartedOrStartedTrip_returnsConflict() throws Exception {
        TripSnapshotEntity departed = saveTrip(ORIGIN_ID, DESTINATION_ID, now().minusMinutes(1), TripStatus.SCHEDULED);
        TripSnapshotEntity started = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.IN_TRANSIT);

        for (UUID tripId : List.of(departed.getTripId(), started.getTripId())) {
            mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderJson(tripId, "[1]", null)))
                    .andExpect(status().isConflict());
        }

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[1]", null)))
                .andExpect(status().isUnauthorized());

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_asCashier_returnsForbidden() throws Exception {
        mockMvc.perform(post("/orders").with(cashier(ORIGIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[1]", null)))
                .andExpect(status().isForbidden());

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void create_withCustomerTokenWithoutCustomerId_returnsForbidden() throws Exception {
        mockMvc.perform(post("/orders").with(userWithRole("CUSTOMER", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(trip.getTripId(), "[1]", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void get_ownOrder_returnsItWithTicketsSortedBySeat() throws Exception {
        PurchaseOrderEntity order = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED,
                now().plusMinutes(5), 9, 2);

        mockMvc.perform(get("/orders/{id}", order.getId()).with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.getId().toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.destinationName").value("Xela"))
                .andExpect(jsonPath("$.busPlate").value("P-123ABC"))
                .andExpect(jsonPath("$.departureAt").value(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(trip.getDepartureAt())))
                .andExpect(jsonPath("$.tickets[0].seatNumber").value(2))
                .andExpect(jsonPath("$.tickets[1].seatNumber").value(9));
    }

    @Test
    void get_orderOfAnotherCustomerOrUnknown_returnsNotFound() throws Exception {
        PurchaseOrderEntity order = saveOnlineOrder(trip, OTHER_CUSTOMER_ID, OrderStatus.CONFIRMED,
                TicketStatus.CONFIRMED, now().plusMinutes(5), 1);

        mockMvc.perform(get("/orders/{id}", order.getId()).with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/{id}", UUID.randomUUID()).with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_withInvalidId_returnsBadRequestNamingParameter() throws Exception {
        mockMvc.perform(get("/orders/{id}", "no-es-uuid").with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("orderId")));
    }

    private PurchaseOrderEntity loadOrder(UUID orderId) {
        return transactionTemplate.execute(status -> {
            PurchaseOrderEntity order = purchaseOrderRepository.findById(orderId).orElseThrow();
            order.getTickets().sort(Comparator.comparingInt(TicketEntity::getSeatNumber));
            return order;
        });
    }

    private static String orderJson(UUID tripId, String seats, String paymentDate) {
        return "{\"tripId\":\"%s\",\"seats\":%s%s}".formatted(tripId, seats,
                paymentDate == null ? "" : ",\"paymentDate\":\"" + paymentDate + "\"");
    }
}
