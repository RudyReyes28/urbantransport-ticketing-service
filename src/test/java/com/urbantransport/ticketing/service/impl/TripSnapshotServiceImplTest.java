package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.ProcessedEventEntity;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.event.TripUpsertEvent.SeatLayoutPayload;
import com.urbantransport.ticketing.event.TripUpsertEvent.TripPayload;
import com.urbantransport.ticketing.repository.ProcessedEventRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.TripCascadeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripSnapshotServiceImplTest {

    private static final LocalDateTime DEPARTURE =
            LocalDateTime.now(ZoneId.of("America/Guatemala")).plusDays(2).truncatedTo(ChronoUnit.MINUTES);
    private static final Instant OCCURRED_AT = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRIP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROUTE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORIGIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DESTINATION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID BUS_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final BigDecimal PRICE = new BigDecimal("75.00");

    @Mock
    private TripSnapshotRepository tripSnapshotRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private TripCascadeService tripCascadeService;

    @Captor
    private ArgumentCaptor<TripSnapshotEntity> tripCaptor;

    @Captor
    private ArgumentCaptor<ProcessedEventEntity> processedCaptor;

    private TripSnapshotServiceImpl tripSnapshotService;

    @BeforeEach
    void setUp() {
        tripSnapshotService = new TripSnapshotServiceImpl(tripSnapshotRepository, processedEventRepository,
                tripCascadeService);
    }

    @Nested
    class ApplyUpsert {

        @Test
        void newRegularTrip_isSavedWithAllData() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(TripStatus.SCHEDULED));

            verify(tripSnapshotRepository).save(tripCaptor.capture());
            TripSnapshotEntity saved = tripCaptor.getValue();
            assertEquals(TRIP_ID, saved.getTripId());
            assertEquals(ROUTE_ID, saved.getRouteId());
            assertEquals(ORIGIN_ID, saved.getOriginBranchId());
            assertEquals(DESTINATION_ID, saved.getDestinationBranchId());
            assertEquals("Central", saved.getOriginName());
            assertEquals("Xela", saved.getDestinationName());
            assertEquals(BUS_ID, saved.getBusId());
            assertEquals("P-123ABC", saved.getBusPlate());
            assertEquals(10, saved.getSeatRows());
            assertEquals(4, saved.getSeatColumns());
            assertEquals(2, saved.getAisleAfterColumn());
            assertEquals(40, saved.getCapacity());
            assertEquals(PRICE, saved.getTicketPrice());
            assertEquals(DEPARTURE, saved.getDepartureAt());
            assertEquals(DEPARTURE.plusHours(4), saved.getEstimatedArrival());
            assertEquals(TripStatus.SCHEDULED, saved.getStatus());
            assertEquals(OCCURRED_AT, saved.getLastEventAt());
            verifyNoInteractions(tripCascadeService);
            assertProcessed();
        }

        @Test
        void newTripWithoutStatusOrCapacity_startsScheduledWithComputedCapacity() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload("REGULAR", null, new SeatLayoutPayload(5, 3, 0),
                    PRICE, null));

            verify(tripSnapshotRepository).save(tripCaptor.capture());
            assertEquals(TripStatus.SCHEDULED, tripCaptor.getValue().getStatus());
            assertEquals(15, tripCaptor.getValue().getCapacity());
            assertEquals(0, tripCaptor.getValue().getAisleAfterColumn());
        }

        @Test
        void newerEventOnExistingTrip_updatesDataAndStatus() {
            TripSnapshotEntity existing = existingTrip(TripStatus.SCHEDULED, OCCURRED_AT.minusSeconds(60));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(existing));

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(TripStatus.IN_TRANSIT));

            verify(tripSnapshotRepository).save(existing);
            assertEquals("Central", existing.getOriginName());
            assertEquals("P-123ABC", existing.getBusPlate());
            assertEquals(40, existing.getCapacity());
            assertEquals(PRICE, existing.getTicketPrice());
            assertEquals(TripStatus.IN_TRANSIT, existing.getStatus());
            assertEquals(OCCURRED_AT, existing.getLastEventAt());
            verifyNoInteractions(tripCascadeService);
            assertProcessed();
        }

        @ParameterizedTest
        @CsvSource({"IN_TRANSIT, SCHEDULED", "COMPLETED, IN_TRANSIT", "IN_TRANSIT, IN_TRANSIT"})
        void updateWithOlderOrSameStatus_neverMovesStatusBack(TripStatus current, TripStatus incoming) {
            TripSnapshotEntity existing = existingTrip(current, OCCURRED_AT.minusSeconds(60));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(existing));

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(incoming));

            assertEquals(current, existing.getStatus());
            assertEquals(PRICE, existing.getTicketPrice());
            verify(tripSnapshotRepository).save(existing);
        }

        @Test
        void updateWithoutStatus_keepsCurrentStatus() {
            TripSnapshotEntity existing = existingTrip(TripStatus.IN_TRANSIT, OCCURRED_AT.minusSeconds(60));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(existing));

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(null));

            assertEquals(TripStatus.IN_TRANSIT, existing.getStatus());
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 60})
        void olderOrSameTimeEvent_isDiscardedButMarked(long secondsAfter) {
            TripSnapshotEntity existing = existingTrip(TripStatus.SCHEDULED, OCCURRED_AT.plusSeconds(secondsAfter));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(existing));

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(TripStatus.SCHEDULED));

            verify(tripSnapshotRepository, never()).save(any());
            assertEquals(new BigDecimal("10.00"), existing.getTicketPrice());
            assertProcessed();
        }

        @Test
        void deletedTrip_isNotRevived() {
            TripSnapshotEntity existing = existingTrip(TripStatus.DELETED, OCCURRED_AT.minusSeconds(60));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(existing));

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(TripStatus.SCHEDULED));

            verify(tripSnapshotRepository, never()).save(any());
            assertEquals(TripStatus.DELETED, existing.getStatus());
            assertProcessed();
        }

        @Test
        void privateTrip_isIgnoredButMarked() {
            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload("PRIVATE", null, null, null, null));

            verifyNoInteractions(tripSnapshotRepository, tripCascadeService);
            assertProcessed();
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(TripStatus.SCHEDULED));

            verifyNoInteractions(tripSnapshotRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void withoutPayloadTripIdOrOccurredAt_throws() {
            TripPayload withoutTripId = new TripPayload(null, "REGULAR", null, null, null, null, null, null, null,
                    null, null, null, null, null, null);

            assertIllegalArgument("Evento de viaje sin tripId u occurredAt",
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, null));
            assertIllegalArgument("Evento de viaje sin tripId u occurredAt",
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, withoutTripId));
            assertIllegalArgument("Evento de viaje sin tripId u occurredAt",
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, null, payload(TripStatus.SCHEDULED)));
            verifyNoInteractions(tripSnapshotRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void withoutType_throws() {
            assertIllegalArgument("Evento de viaje " + TRIP_ID + " sin type",
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload(null, null, null, null, null)));

            verify(processedEventRepository, never()).save(any());
        }

        @ParameterizedTest
        @MethodSource("com.urbantransport.ticketing.service.impl.TripSnapshotServiceImplTest#missingFields")
        void missingRequiredField_throwsNamingIt(TripPayload payload, String field) {
            assertIllegalArgument("Evento de viaje sin " + field,
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload));

            verifyNoInteractions(tripSnapshotRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @ParameterizedTest
        @CsvSource({"0, 4, 2", "10, 0, 0", "10, 7, 2", "10, 4, -1", "10, 4, 4"})
        void invalidSeatLayout_throws(int rows, int columns, int aisle) {
            TripPayload payload = payload("REGULAR", null, new SeatLayoutPayload(rows, columns, aisle), PRICE, null);

            assertIllegalArgument("Distribución de asientos inválida en el viaje " + TRIP_ID,
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload));

            verifyNoInteractions(tripSnapshotRepository);
        }

        @Test
        void capacityDifferentFromLayout_throws() {
            TripPayload payload = payload("REGULAR", 41, new SeatLayoutPayload(10, 4, 2), PRICE, null);

            assertIllegalArgument("capacity no coincide con filas x columnas en el viaje " + TRIP_ID,
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload));

            verifyNoInteractions(tripSnapshotRepository);
        }

        @Test
        void negativePrice_throws() {
            TripPayload payload = payload("REGULAR", 40, new SeatLayoutPayload(10, 4, 2), new BigDecimal("-1"), null);

            assertIllegalArgument("ticketPrice negativo en el viaje " + TRIP_ID,
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload));

            verifyNoInteractions(tripSnapshotRepository);
        }

        @Test
        void freeTrip_isAccepted() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT,
                    payload("REGULAR", 40, new SeatLayoutPayload(10, 4, 2), BigDecimal.ZERO, null));

            verify(tripSnapshotRepository).save(tripCaptor.capture());
            assertEquals(BigDecimal.ZERO, tripCaptor.getValue().getTicketPrice());
        }

        @Test
        void deletedStatusInPayload_throws() {
            TripPayload payload = payload(TripStatus.DELETED);

            assertIllegalArgument("La eliminación de un viaje llega con TripDeleted, no con " + TRIP_ID,
                    () -> tripSnapshotService.applyUpsert(EVENT_ID, OCCURRED_AT, payload));

            verifyNoInteractions(tripSnapshotRepository);
        }
    }

    @Nested
    class ApplyStatusChange {

        @ParameterizedTest
        @CsvSource({"SCHEDULED, IN_TRANSIT", "SCHEDULED, COMPLETED", "IN_TRANSIT, COMPLETED"})
        void startOrCompletion_movesStatusAndExpiresPendingOrders(TripStatus current, TripStatus next) {
            TripSnapshotEntity trip = existingTrip(current, OCCURRED_AT.minusSeconds(60));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));

            tripSnapshotService.applyStatusChange(EVENT_ID, OCCURRED_AT, TRIP_ID, next);

            assertEquals(next, trip.getStatus());
            assertEquals(OCCURRED_AT, trip.getLastEventAt());
            verify(tripSnapshotRepository).save(trip);
            verify(tripCascadeService).expirePendingOrders(TRIP_ID);
            verify(tripCascadeService, never()).cancelTripOrders(any());
            assertProcessed();
        }

        @ParameterizedTest
        @EnumSource(value = TripStatus.class, names = {"SCHEDULED", "IN_TRANSIT"})
        void deletion_movesStatusAndCancelsOrders(TripStatus current) {
            TripSnapshotEntity trip = existingTrip(current, OCCURRED_AT.minusSeconds(60));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));

            tripSnapshotService.applyStatusChange(EVENT_ID, OCCURRED_AT, TRIP_ID, TripStatus.DELETED);

            assertEquals(TripStatus.DELETED, trip.getStatus());
            verify(tripCascadeService).cancelTripOrders(TRIP_ID);
            verify(tripCascadeService, never()).expirePendingOrders(any());
            assertProcessed();
        }

        @ParameterizedTest
        @CsvSource({"IN_TRANSIT, IN_TRANSIT", "COMPLETED, IN_TRANSIT", "COMPLETED, DELETED", "DELETED, COMPLETED",
                "DELETED, IN_TRANSIT", "IN_TRANSIT, SCHEDULED"})
        void forbiddenTransition_isDiscardedButMarked(TripStatus current, TripStatus next) {
            TripSnapshotEntity trip = existingTrip(current, OCCURRED_AT.minusSeconds(60));
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));

            tripSnapshotService.applyStatusChange(EVENT_ID, OCCURRED_AT, TRIP_ID, next);

            assertEquals(current, trip.getStatus());
            verify(tripSnapshotRepository, never()).save(any());
            verifyNoInteractions(tripCascadeService);
            assertProcessed();
        }

        @Test
        void olderEvent_isDiscardedButMarked() {
            Instant lastEvent = OCCURRED_AT.plusSeconds(1);
            TripSnapshotEntity trip = existingTrip(TripStatus.SCHEDULED, lastEvent);
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));

            tripSnapshotService.applyStatusChange(EVENT_ID, OCCURRED_AT, TRIP_ID, TripStatus.IN_TRANSIT);

            assertEquals(TripStatus.SCHEDULED, trip.getStatus());
            assertSame(lastEvent, trip.getLastEventAt());
            verify(tripSnapshotRepository, never()).save(any());
            verifyNoInteractions(tripCascadeService);
            assertProcessed();
        }

        @Test
        void unknownTrip_isIgnoredButMarked() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            tripSnapshotService.applyStatusChange(EVENT_ID, OCCURRED_AT, TRIP_ID, TripStatus.IN_TRANSIT);

            verify(tripSnapshotRepository, never()).save(any());
            verifyNoInteractions(tripCascadeService);
            assertProcessed();
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            tripSnapshotService.applyStatusChange(EVENT_ID, OCCURRED_AT, TRIP_ID, TripStatus.DELETED);

            verifyNoInteractions(tripSnapshotRepository, tripCascadeService);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void withoutTripIdOrOccurredAt_throws() {
            assertIllegalArgument("Evento de estado de viaje sin tripId u occurredAt",
                    () -> tripSnapshotService.applyStatusChange(EVENT_ID, OCCURRED_AT, null, TripStatus.IN_TRANSIT));
            assertIllegalArgument("Evento de estado de viaje sin tripId u occurredAt",
                    () -> tripSnapshotService.applyStatusChange(EVENT_ID, null, TRIP_ID, TripStatus.IN_TRANSIT));

            verifyNoInteractions(tripSnapshotRepository, tripCascadeService);
            verify(processedEventRepository, never()).save(any());
        }
    }

    static Stream<Arguments> missingFields() {
        SeatLayoutPayload layout = new SeatLayoutPayload(10, 4, 2);
        return Stream.of(
                Arguments.of(full(null, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE, layout), "routeId"),
                Arguments.of(full(ROUTE_ID, null, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE, layout), "originBranchId"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, null, "Central", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE, layout), "destinationBranchId"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "  ", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE, layout), "originName"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", null, BUS_ID, "P-1", PRICE, DEPARTURE, layout), "destinationName"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", null, "P-1", PRICE, DEPARTURE, layout), "busId"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "", PRICE, DEPARTURE, layout), "busPlate"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", null, DEPARTURE, layout), "ticketPrice"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", PRICE, null, layout), "departureAt"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE, null), "seatLayout"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE,
                        new SeatLayoutPayload(null, 4, 2)), "seatLayout.rows"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE,
                        new SeatLayoutPayload(10, null, 2)), "seatLayout.columns"),
                Arguments.of(full(ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID, "P-1", PRICE, DEPARTURE,
                        new SeatLayoutPayload(10, 4, null)), "seatLayout.aisleAfterColumn"));
    }

    private static TripPayload full(UUID routeId, UUID originId, UUID destinationId, String originName,
                                    String destinationName, UUID busId, String plate, BigDecimal price,
                                    LocalDateTime departure, SeatLayoutPayload layout) {
        return new TripPayload(TRIP_ID, "REGULAR", routeId, originId, destinationId, originName, destinationName,
                busId, plate, null, layout, price, departure, null, null);
    }

    private static TripPayload payload(TripStatus status) {
        return payload("REGULAR", 40, new SeatLayoutPayload(10, 4, 2), PRICE, status);
    }

    private static TripPayload payload(String type, Integer capacity, SeatLayoutPayload layout, BigDecimal price,
                                       TripStatus status) {
        return new TripPayload(TRIP_ID, type, ROUTE_ID, ORIGIN_ID, DESTINATION_ID, "Central", "Xela", BUS_ID,
                "P-123ABC", capacity, layout, price, DEPARTURE, DEPARTURE.plusHours(4), status);
    }

    private static TripSnapshotEntity existingTrip(TripStatus status, Instant lastEventAt) {
        return TripSnapshotEntity.builder()
                .tripId(TRIP_ID)
                .originName("Viejo")
                .busPlate("P-OLD")
                .seatRows(5)
                .seatColumns(4)
                .aisleAfterColumn(2)
                .capacity(20)
                .ticketPrice(new BigDecimal("10.00"))
                .departureAt(DEPARTURE.minusDays(1))
                .status(status)
                .lastEventAt(lastEventAt)
                .build();
    }

    private void assertProcessed() {
        verify(processedEventRepository).save(processedCaptor.capture());
        assertEquals(EVENT_ID, processedCaptor.getValue().getEventId());
        assertNull(processedCaptor.getValue().getProcessedAt());
    }

    private static void assertIllegalArgument(String message, Executable action) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action);
        assertEquals(message, exception.getMessage());
    }
}
