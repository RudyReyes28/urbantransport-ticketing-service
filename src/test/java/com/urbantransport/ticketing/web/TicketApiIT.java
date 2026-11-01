package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.AbstractIntegrationTest;
import com.urbantransport.ticketing.client.CustomerSummary;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketApiIT extends AbstractIntegrationTest {

    private static final UUID BRANCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private TripSnapshotEntity trip;

    @BeforeEach
    void seedTickets() {
        trip = saveTrip(BRANCH_ID, OTHER_BRANCH_ID, now().plusDays(1), TripStatus.SCHEDULED);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED, now().plusMinutes(5), 4, 1);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CANCELLED, TicketStatus.CANCELLED, now().plusMinutes(5), 9);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CANCELLED, TicketStatus.REFUNDED, now().plusMinutes(5), 10);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 12);
        saveOnlineOrder(trip, OTHER_CUSTOMER_ID, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED, now().plusMinutes(5), 20);
        saveCounterOrder(trip, OTHER_CUSTOMER_ID, "CF", 30);
    }

    @Test
    void mine_withoutStatus_returnsHistoryWithoutPendingOrOtherCustomers() throws Exception {
        mockMvc.perform(get("/tickets/me").with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.content[*].seatNumber", containsInAnyOrder(1, 4, 9, 10)))
                .andExpect(jsonPath("$.content[0].originName").value("Central"))
                .andExpect(jsonPath("$.content[0].busPlate").value("P-123ABC"))
                .andExpect(jsonPath("$.content[0].tripStatus").value("SCHEDULED"));
    }

    @Test
    void mine_withStatus_filtersAndPaginates() throws Exception {
        mockMvc.perform(get("/tickets/me").with(customer(CUSTOMER_ID, "12345678")).param("status", "PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].seatNumber").value(12))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));

        mockMvc.perform(get("/tickets/me").with(customer(CUSTOMER_ID, "12345678")).param("status", "REFUNDED"))
                .andExpect(jsonPath("$.content[0].seatNumber").value(10))
                .andExpect(jsonPath("$.content[0].cancelReason").value("TRIP_DELETED"));

        mockMvc.perform(get("/tickets/me").with(customer(CUSTOMER_ID, "12345678")).param("size", "3"))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void mine_withInvalidStatus_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/tickets/me").with(customer(CUSTOMER_ID, "12345678")).param("status", "LOST"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mine_asCashierOrWithoutCustomerId_returnsForbidden() throws Exception {
        mockMvc.perform(get("/tickets/me").with(cashier(BRANCH_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/tickets/me").with(userWithRole("CUSTOMER", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void mine_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/tickets/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void byNit_returnsHistoryOfRegisteredCustomerForwardingToken() throws Exception {
        when(customerClient.findByNit("12345678", "token"))
                .thenReturn(Optional.of(new CustomerSummary(CUSTOMER_ID, "12345678", "Ana", false, true)));

        mockMvc.perform(get("/tickets/by-nit/{nit}", "1234567-8").with(cashier(BRANCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));

        verify(customerClient).findByNit("12345678", "token");
    }

    @Test
    void byNit_withGenericOrInvalidNit_returnsBadRequestWithoutCallingCustomer() throws Exception {
        mockMvc.perform(get("/tickets/by-nit/{nit}", "cf").with(systemAdmin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/tickets/by-nit/{nit}", "12AB").with(systemAdmin()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(customerClient);
    }

    @Test
    void byNit_unregisteredNit_returnsNotFound() throws Exception {
        when(customerClient.findByNit("87654321", "token")).thenReturn(Optional.empty());

        mockMvc.perform(get("/tickets/by-nit/{nit}", "87654321").with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void byNit_asCustomer_returnsForbidden() throws Exception {
        mockMvc.perform(get("/tickets/by-nit/{nit}", "12345678").with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(customerClient);
    }

    @Test
    void tripTickets_asStaffOfOriginBranch_returnsConfirmedBySeat() throws Exception {
        mockMvc.perform(get("/trips/{id}/tickets", trip.getTripId()).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].seatNumber").value(1))
                .andExpect(jsonPath("$[1].seatNumber").value(4))
                .andExpect(jsonPath("$[2].seatNumber").value(20))
                .andExpect(jsonPath("$[3].seatNumber").value(30))
                .andExpect(jsonPath("$[3].channel").value("COUNTER"))
                .andExpect(jsonPath("$[3].customerNit").value("CF"));

        mockMvc.perform(get("/trips/{id}/tickets", trip.getTripId()).with(cashier(BRANCH_ID))
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].seatNumber").value(9));
    }

    @Test
    void tripTickets_ofAnotherBranch_returnsForbidden() throws Exception {
        mockMvc.perform(get("/trips/{id}/tickets", trip.getTripId()).with(branchAdmin(OTHER_BRANCH_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tripTickets_asSystemAdmin_seesAnyBranchIncludingDeletedTrip() throws Exception {
        trip.setStatus(TripStatus.DELETED);
        tripSnapshotRepository.save(trip);

        mockMvc.perform(get("/trips/{id}/tickets", trip.getTripId()).with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void tripTickets_withTokenWithoutBranch_returnsForbidden() throws Exception {
        mockMvc.perform(get("/trips/{id}/tickets", trip.getTripId()).with(cashier(null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tripTickets_unknownTrip_returnsNotFound() throws Exception {
        mockMvc.perform(get("/trips/{id}/tickets", UUID.randomUUID()).with(systemAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void tripTickets_asCustomerOrWithoutToken_isRejected() throws Exception {
        mockMvc.perform(get("/trips/{id}/tickets", trip.getTripId()).with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/trips/{id}/tickets", trip.getTripId()))
                .andExpect(status().isUnauthorized());
    }
}
