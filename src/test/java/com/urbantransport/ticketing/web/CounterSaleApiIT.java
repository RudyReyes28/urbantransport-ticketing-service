package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.AbstractIntegrationTest;
import com.urbantransport.ticketing.client.CustomerSummary;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.util.GenericCustomer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CounterSaleApiIT extends AbstractIntegrationTest {

    private static final UUID BRANCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String MOCK_TOKEN = "token";

    @Autowired
    private TransactionTemplate transactionTemplate;

    private TripSnapshotEntity trip;

    @BeforeEach
    void seedTrip() {
        trip = saveTrip(BRANCH_ID, OTHER_BRANCH_ID, now().plusDays(1), TripStatus.SCHEDULED);
    }

    @Test
    void sell_withoutNit_createsConfirmedOrderForGenericCustomer() throws Exception {
        MvcResult result = mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[2, 1]", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.channel").value("COUNTER"))
                .andExpect(jsonPath("$.customerNit").value("CF"))
                .andExpect(jsonPath("$.total").value(100.0))
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.tickets[0].seatLabel").value("1A"))
                .andExpect(jsonPath("$.tickets[1].status").value("CONFIRMED"))
                .andReturn();

        UUID orderId = idAt(result, "$.orderId");
        PurchaseOrderEntity order = loadOrder(orderId);
        assertEquals(SalesChannel.COUNTER, order.getChannel());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        assertEquals(GenericCustomer.ID, order.getCustomerId());
        assertEquals(BRANCH_ID, order.getBranchId());
        assertEquals(USER_ID, order.getSoldByUserId());
        assertNull(order.getExpiresAt());
        assertNull(order.getBuyerEmail());
        assertEquals(today(), order.getPaymentDate());
        assertEquals(2, order.getTickets().size());
        assertTrue(order.getTickets().stream().allMatch(ticket -> ticket.getStatus() == TicketStatus.CONFIRMED));
        assertEquals(List.of("TicketPurchased"), outboxEventTypes(orderId));
        verifyNoInteractions(customerClient);
    }

    @Test
    void sell_asBranchAdminWithRegisteredNit_usesCustomerAndForwardsToken() throws Exception {
        when(customerClient.findByNit("12345678", MOCK_TOKEN))
                .thenReturn(Optional.of(new CustomerSummary(CUSTOMER_ID, "12345678", "Ana", false, true)));

        MvcResult result = mockMvc.perform(post("/counter-sales").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", "1234567-8")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerNit").value("12345678"))
                .andReturn();

        PurchaseOrderEntity order = loadOrder(idAt(result, "$.orderId"));
        assertEquals(CUSTOMER_ID, order.getCustomerId());
        assertEquals(CUSTOMER_ID, order.getTickets().get(0).getCustomerId());
        verify(customerClient).findByNit("12345678", MOCK_TOKEN);
    }

    @Test
    void sell_withUnregisteredNit_returnsNotFoundWithoutSaving() throws Exception {
        when(customerClient.findByNit("12345678", MOCK_TOKEN)).thenReturn(Optional.empty());

        mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", "12345678")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("12345678")));

        assertEquals(0, purchaseOrderRepository.count());
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_events"));
    }

    @Test
    void sell_whenCustomerIsUnavailable_returnsServiceUnavailableWithoutSaving() throws Exception {
        when(customerClient.findByNit(anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "customer caido"));

        mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", "12345678")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("customer caido"));

        assertEquals(0, purchaseOrderRepository.count());
        assertEquals(0, ticketRepository.count());
    }

    @Test
    void sell_withInvalidNit_returnsBadRequestWithoutCallingCustomer() throws Exception {
        mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", "12AB")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(customerClient);
        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void sell_tripOfAnotherBranch_returnsForbidden() throws Exception {
        mockMvc.perform(post("/counter-sales").with(cashier(OTHER_BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void sell_withTokenWithoutBranch_returnsForbidden() throws Exception {
        mockMvc.perform(post("/counter-sales").with(cashier(null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("sucursal")));

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void sell_asCustomerOrSystemAdmin_returnsForbidden() throws Exception {
        mockMvc.perform(post("/counter-sales").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/counter-sales").with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", null)))
                .andExpect(status().isForbidden());

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void sell_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/counter-sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5]", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sell_withTakenSeat_returnsConflictWithoutSaving() throws Exception {
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 5);

        mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(trip.getTripId(), "[5, 6]", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeats[0]").value(5));

        assertEquals(1, purchaseOrderRepository.count());
        assertEquals(1, ticketRepository.count());
    }

    @Test
    void sell_departedTrip_returnsConflict() throws Exception {
        TripSnapshotEntity departed = saveTrip(BRANCH_ID, OTHER_BRANCH_ID, now().minusMinutes(5), TripStatus.SCHEDULED);

        mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleJson(departed.getTripId(), "[5]", null)))
                .andExpect(status().isConflict());

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void sell_withInvalidBody_returnsValidationError() throws Exception {
        mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[null],\"customerNit\":\"123456789012345678901\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("tripId")))
                .andExpect(jsonPath("$.message", containsString("seats")))
                .andExpect(jsonPath("$.message", containsString("customerNit")));

        assertEquals(0, purchaseOrderRepository.count());
    }

    @Test
    void sell_withPaymentDate_keepsIt() throws Exception {
        MvcResult result = mockMvc.perform(post("/counter-sales").with(cashier(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":\"%s\",\"seats\":[1],\"paymentDate\":\"%s\"}"
                                .formatted(trip.getTripId(), today().minusDays(1))))
                .andExpect(status().isCreated())
                .andReturn();

        PurchaseOrderEntity order = loadOrder(idAt(result, "$.orderId"));
        assertEquals(today().minusDays(1), order.getPaymentDate());
        assertEquals(0, new BigDecimal("50.00").compareTo(order.getTotal()));
    }

    private PurchaseOrderEntity loadOrder(UUID orderId) {
        return transactionTemplate.execute(status -> {
            PurchaseOrderEntity order = purchaseOrderRepository.findById(orderId).orElseThrow();
            order.getTickets().size();
            return order;
        });
    }

    private static String saleJson(UUID tripId, String seats, String nit) {
        return "{\"tripId\":\"%s\",\"seats\":%s%s}".formatted(tripId, seats,
                nit == null ? "" : ",\"customerNit\":\"" + nit + "\"");
    }
}
