package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.AbstractIntegrationTest;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripApiIT extends AbstractIntegrationTest {

    private static final UUID CENTRAL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID XELA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PETEN = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CUSTOMER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void available_withoutToken_returnsOnlySellableTripsByDepartureWithFreeSeats() throws Exception {
        LocalDateTime tomorrow = today().plusDays(1).atTime(10, 0);
        TripSnapshotEntity later = saveTrip(CENTRAL, XELA, tomorrow, TripStatus.SCHEDULED);
        TripSnapshotEntity earlier = saveTrip(PETEN, XELA, tomorrow.minusHours(2), TripStatus.SCHEDULED);
        saveTrip(CENTRAL, XELA, now().minusMinutes(1), TripStatus.SCHEDULED);
        saveTrip(CENTRAL, XELA, tomorrow, TripStatus.IN_TRANSIT);
        saveTrip(CENTRAL, XELA, tomorrow, TripStatus.DELETED);
        saveOnlineOrder(later, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 1);
        saveCounterOrder(later, CUSTOMER_ID, "CF", 2, 3);
        saveOnlineOrder(later, CUSTOMER_ID, OrderStatus.EXPIRED, TicketStatus.CANCELLED, now().minusMinutes(1), 4);

        mockMvc.perform(get("/trips/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].tripId").value(earlier.getTripId().toString()))
                .andExpect(jsonPath("$.content[0].availableSeats").value(40))
                .andExpect(jsonPath("$.content[1].tripId").value(later.getTripId().toString()))
                .andExpect(jsonPath("$.content[1].availableSeats").value(37))
                .andExpect(jsonPath("$.content[1].capacity").value(40))
                .andExpect(jsonPath("$.content[1].ticketPrice").value(50.0))
                .andExpect(jsonPath("$.content[1].originBranchId").value(CENTRAL.toString()));
    }

    @Test
    void available_filtersByOriginDestinationAndDate() throws Exception {
        LocalDateTime inTwoDays = today().plusDays(2).atTime(8, 0);
        TripSnapshotEntity match = saveTrip(CENTRAL, XELA, inTwoDays, TripStatus.SCHEDULED);
        saveTrip(PETEN, XELA, inTwoDays, TripStatus.SCHEDULED);
        saveTrip(CENTRAL, PETEN, inTwoDays, TripStatus.SCHEDULED);
        saveTrip(CENTRAL, XELA, inTwoDays.plusDays(1), TripStatus.SCHEDULED);
        saveTrip(CENTRAL, XELA, inTwoDays.toLocalDate().atStartOfDay().minusSeconds(1), TripStatus.SCHEDULED);

        mockMvc.perform(get("/trips/available")
                        .param("originBranchId", CENTRAL.toString())
                        .param("destinationBranchId", XELA.toString())
                        .param("date", inTwoDays.toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].tripId").value(match.getTripId().toString()));

        mockMvc.perform(get("/trips/available").param("originBranchId", CENTRAL.toString()))
                .andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(get("/trips/available").param("destinationBranchId", PETEN.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void available_paginatesAndClampsSize() throws Exception {
        for (int i = 1; i <= 3; i++) {
            saveTrip(CENTRAL, XELA, now().plusDays(i), TripStatus.SCHEDULED);
        }

        mockMvc.perform(get("/trips/available").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/trips/available").param("page", "-5").param("size", "500"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    @Test
    void available_withInvalidDate_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/trips/available").param("date", "31-12-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("date")));
    }

    @Test
    void seats_withoutToken_returnsMapWithOccupiedSeats() throws Exception {
        TripSnapshotEntity trip = saveTrip(CENTRAL, XELA, now().plusDays(1), TripStatus.SCHEDULED);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING, now().plusMinutes(5), 2);
        saveCounterOrder(trip, CUSTOMER_ID, "CF", 7);
        saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.FAILED, TicketStatus.CANCELLED, now().minusMinutes(1), 3);

        mockMvc.perform(get("/trips/{id}/seats", trip.getTripId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.rows").value(10))
                .andExpect(jsonPath("$.columns").value(4))
                .andExpect(jsonPath("$.aisleAfterColumn").value(2))
                .andExpect(jsonPath("$.availableSeats").value(38))
                .andExpect(jsonPath("$.seats", hasSize(40)))
                .andExpect(jsonPath("$.seats[1].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.seats[2].status").value("FREE"))
                .andExpect(jsonPath("$.seats[6].label").value("2C"))
                .andExpect(jsonPath("$.seats[6].status").value("OCCUPIED"));
    }

    @Test
    void seats_ofStartedTrip_isClosed() throws Exception {
        TripSnapshotEntity trip = saveTrip(CENTRAL, XELA, now().minusHours(1), TripStatus.IN_TRANSIT);

        mockMvc.perform(get("/trips/{id}/seats", trip.getTripId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void seats_ofDeletedOrUnknownTrip_returnsNotFound() throws Exception {
        TripSnapshotEntity deleted = saveTrip(CENTRAL, XELA, now().plusDays(1), TripStatus.DELETED);

        mockMvc.perform(get("/trips/{id}/seats", deleted.getTripId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/trips/{id}/seats", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path", containsString("/seats")));
    }
}
