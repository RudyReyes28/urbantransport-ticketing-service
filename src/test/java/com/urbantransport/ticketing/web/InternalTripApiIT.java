package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.AbstractIntegrationTest;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalTripApiIT extends AbstractIntegrationTest {

    private static final UUID BRANCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TripSnapshotEntity trip;

    @BeforeEach
    void seedTrip() {
        trip = saveTrip(BRANCH_ID, UUID.randomUUID(), now().plusDays(1), TripStatus.SCHEDULED);
    }

    @Test
    void soldSummary_countsOnlyActiveTicketsAndHighestSeat() throws Exception {
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED, now().plusMinutes(5), 3, 12);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 20);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CANCELLED, TicketStatus.CANCELLED, now().plusMinutes(5), 38);
        saveCounterOrder(trip, CUSTOMER_ID, "CF", 7);

        mockMvc.perform(get("/internal/trips/{id}/sold-summary", trip.getTripId()).with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(trip.getTripId().toString()))
                .andExpect(jsonPath("$.activeTickets").value(4))
                .andExpect(jsonPath("$.maxSeatNumber").value(20));
    }

    @Test
    void soldSummary_withoutTickets_returnsZeroAndNullSeat() throws Exception {
        mockMvc.perform(get("/internal/trips/{id}/sold-summary", trip.getTripId()).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTickets").value(0))
                .andExpect(jsonPath("$.maxSeatNumber").doesNotExist());
    }

    @Test
    void soldSummary_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/trips/{id}/sold-summary", trip.getTripId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void soldSummary_asCashierOrCustomer_returnsForbidden() throws Exception {
        mockMvc.perform(get("/internal/trips/{id}/sold-summary", trip.getTripId()).with(cashier(BRANCH_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/trips/{id}/sold-summary", trip.getTripId())
                        .with(customer(CUSTOMER_ID, "12345678")))
                .andExpect(status().isForbidden());
    }
}
