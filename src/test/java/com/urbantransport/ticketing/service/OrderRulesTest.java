package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.config.TicketingProperties;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.exception.SeatsUnavailableException;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderRulesTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDateTime NOW = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final UUID TRIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final int MAX_SEATS = 3;

    @Mock
    private TripSnapshotRepository tripSnapshotRepository;

    @Mock
    private TicketRepository ticketRepository;

    private OrderRules rules;

    @BeforeEach
    void setUp() {
        rules = new OrderRules(tripSnapshotRepository, ticketRepository,
                new TicketingProperties("America/Guatemala", MAX_SEATS, Duration.ofMinutes(5), 2), CLOCK);
    }

    @Nested
    class NormalizeSeats {

        @Test
        void unorderedSeats_areReturnedSorted() {
            assertEquals(List.of(1, 4, 7), rules.normalizeSeats(List.of(7, 1, 4)));
        }

        @Test
        void repeatedSeats_throwsBadRequest() {
            assertApiError(HttpStatus.BAD_REQUEST, "Hay asientos repetidos en la petición",
                    () -> rules.normalizeSeats(List.of(3, 5, 3)));
        }

        @Test
        void moreSeatsThanAllowed_throwsBadRequest() {
            assertApiError(HttpStatus.BAD_REQUEST, "Se pueden comprar hasta 3 asientos por orden",
                    () -> rules.normalizeSeats(List.of(1, 2, 3, 4)));
        }

        @Test
        void exactlyTheMaximum_isAccepted() {
            assertEquals(List.of(1, 2, 3), rules.normalizeSeats(List.of(3, 2, 1)));
        }
    }

    @Nested
    class ResolvePaymentDate {

        @Test
        void missingDate_returnsTodayInGuatemala() {
            assertEquals(TODAY, rules.resolvePaymentDate(null));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 30})
        void todayOrPastDate_isKept(int daysAgo) {
            LocalDate date = TODAY.minusDays(daysAgo);

            assertEquals(date, rules.resolvePaymentDate(date));
        }

        @Test
        void futureDate_throwsBadRequest() {
            assertApiError(HttpStatus.BAD_REQUEST, "La fecha de pago no puede ser futura",
                    () -> rules.resolvePaymentDate(TODAY.plusDays(1)));
        }
    }

    @Nested
    class FindTrip {

        @Test
        void existingTrip_isReturned() {
            TripSnapshotEntity trip = trip(TripStatus.SCHEDULED, NOW.plusDays(1));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));

            assertSame(trip, rules.findTrip(TRIP_ID));
        }

        @Test
        void missingTrip_throwsNotFound() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, "El viaje no existe", () -> rules.findTrip(TRIP_ID));
        }

        @Test
        void deletedTrip_throwsNotFound() {
            when(tripSnapshotRepository.findById(TRIP_ID))
                    .thenReturn(Optional.of(trip(TripStatus.DELETED, NOW.plusDays(1))));

            assertApiError(HttpStatus.NOT_FOUND, "El viaje no existe", () -> rules.findTrip(TRIP_ID));
        }
    }

    @Nested
    class RequireSellable {

        @Test
        void scheduledTripInTheFuture_passes() {
            assertDoesNotThrow(() -> rules.requireSellable(trip(TripStatus.SCHEDULED, NOW.plusMinutes(1))));
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 60})
        void departureNowOrInThePast_throwsConflict(long minutesAgo) {
            TripSnapshotEntity trip = trip(TripStatus.SCHEDULED, NOW.minusMinutes(minutesAgo));

            assertApiError(HttpStatus.CONFLICT, "El viaje ya no está disponible para la venta",
                    () -> rules.requireSellable(trip));
        }

        @ParameterizedTest
        @EnumSource(value = TripStatus.class, names = "SCHEDULED", mode = EnumSource.Mode.EXCLUDE)
        void notScheduled_throwsConflict(TripStatus status) {
            TripSnapshotEntity trip = trip(status, NOW.plusDays(1));

            assertApiError(HttpStatus.CONFLICT, "El viaje ya no está disponible para la venta",
                    () -> rules.requireSellable(trip));
        }
    }

    @Nested
    class ValidateSeatsInRange {

        @Test
        void seatsInsideLayout_pass() {
            assertDoesNotThrow(() -> rules.validateSeatsInRange(trip(TripStatus.SCHEDULED, NOW), List.of(1, 40)));
        }

        @Test
        void seatsOutsideLayout_throwsBadRequestListingThem() {
            TripSnapshotEntity trip = trip(TripStatus.SCHEDULED, NOW);

            assertApiError(HttpStatus.BAD_REQUEST, "Asientos fuera de rango (1 a 40): [0, 41]",
                    () -> rules.validateSeatsInRange(trip, List.of(0, 5, 41)));
        }
    }

    @Nested
    class TakenAmong {

        @Test
        void returnsOnlyRequestedSeatsThatAreActive() {
            when(ticketRepository.findSeatNumbersByTripIdAndStatusIn(TRIP_ID, TicketStatus.ACTIVE))
                    .thenReturn(List.of(9, 2, 5, 30));

            assertEquals(List.of(2, 9), rules.takenAmong(TRIP_ID, List.of(1, 2, 9)));
        }

        @Test
        void takenSeats_throwSeatsUnavailableWithTheList() {
            when(ticketRepository.findSeatNumbersByTripIdAndStatusIn(TRIP_ID, TicketStatus.ACTIVE))
                    .thenReturn(List.of(4, 2));

            SeatsUnavailableException exception = assertThrows(SeatsUnavailableException.class,
                    () -> rules.assertSeatsFree(TRIP_ID, List.of(2, 3, 4)));

            assertEquals(HttpStatus.CONFLICT, exception.getStatus());
            assertEquals(List.of(2, 4), exception.getSeats());
            assertEquals("Los asientos 2, 4 ya no están disponibles", exception.getMessage());
        }

        @Test
        void freeSeats_pass() {
            when(ticketRepository.findSeatNumbersByTripIdAndStatusIn(TRIP_ID, TicketStatus.ACTIVE))
                    .thenReturn(List.of(10));

            assertDoesNotThrow(() -> rules.assertSeatsFree(TRIP_ID, List.of(1, 2)));
            verifyNoInteractions(tripSnapshotRepository);
        }
    }

    private static TripSnapshotEntity trip(TripStatus status, LocalDateTime departureAt) {
        return TripSnapshotEntity.builder()
                .tripId(TRIP_ID)
                .seatRows(10)
                .seatColumns(4)
                .aisleAfterColumn(2)
                .capacity(40)
                .ticketPrice(new BigDecimal("50.00"))
                .departureAt(departureAt)
                .status(status)
                .build();
    }

    private static void assertApiError(HttpStatus status, String message, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(message, exception.getMessage());
    }
}
