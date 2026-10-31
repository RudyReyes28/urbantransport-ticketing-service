package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.service.TripSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TripEventsListenerTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRIP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROUTE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORIGIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DESTINATION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID BUS_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-29T12:00:00Z");
    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 11, 5, 8, 30);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private TripSnapshotService tripSnapshotService;

    @Captor
    private ArgumentCaptor<TripUpsertEvent.TripPayload> payloadCaptor;

    private TripEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new TripEventsListener(jsonMapper, tripSnapshotService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TripScheduled", "TripUpdated"})
    void scheduledOrUpdated_appliesUpsertWithParsedPayload(String eventType) {
        listener.onEvent(message(eventType, tripPayload(50)));

        verify(tripSnapshotService).applyUpsert(eq(EVENT_ID), eq(OCCURRED_AT), payloadCaptor.capture());
        TripUpsertEvent.TripPayload payload = payloadCaptor.getValue();
        assertEquals(TRIP_ID, payload.tripId());
        assertEquals("REGULAR", payload.type());
        assertEquals(ROUTE_ID, payload.routeId());
        assertEquals(ORIGIN_ID, payload.originBranchId());
        assertEquals(DESTINATION_ID, payload.destinationBranchId());
        assertEquals("Central", payload.originName());
        assertEquals("Xela", payload.destinationName());
        assertEquals(BUS_ID, payload.busId());
        assertEquals("P-123ABC", payload.busPlate());
        assertEquals(40, payload.capacity());
        assertEquals(new TripUpsertEvent.SeatLayoutPayload(10, 4, 2), payload.seatLayout());
        assertEquals(0, new BigDecimal("50").compareTo(payload.ticketPrice()));
        assertEquals(DEPARTURE, payload.departureAt());
        assertEquals(DEPARTURE.plusHours(4), payload.estimatedArrival());
        assertEquals(TripStatus.SCHEDULED, payload.status());
    }

    @Test
    void decimalTicketPrice_isParsed() {
        listener.onEvent(message(EventTypes.TRIP_UPDATED, tripPayload(45.75)));

        verify(tripSnapshotService).applyUpsert(eq(EVENT_ID), eq(OCCURRED_AT), payloadCaptor.capture());
        assertEquals(0, new BigDecimal("45.75").compareTo(payloadCaptor.getValue().ticketPrice()));
    }

    @ParameterizedTest
    @CsvSource({"TripStarted, IN_TRANSIT", "TripCompleted, COMPLETED", "TripDeleted, DELETED"})
    void statusEvent_appliesStatusChange(String eventType, TripStatus status) {
        listener.onEvent(message(eventType, Map.of("tripId", TRIP_ID.toString(), "busId", BUS_ID.toString())));

        verify(tripSnapshotService).applyStatusChange(EVENT_ID, OCCURRED_AT, TRIP_ID, status);
    }

    @Test
    void statusEventWithoutPayload_passesNullTripId() {
        Map<String, Object> envelope = new HashMap<>(envelope(EventTypes.TRIP_STARTED));
        envelope.put("payload", null);

        listener.onEvent(jsonMapper.writeValueAsString(envelope));

        verify(tripSnapshotService).applyStatusChange(EVENT_ID, OCCURRED_AT, null, TripStatus.IN_TRANSIT);
    }

    @Test
    void upsertWithoutOptionalFields_passesNulls() {
        listener.onEvent(message(EventTypes.TRIP_SCHEDULED, Map.of("tripId", TRIP_ID.toString(), "type", "PRIVATE")));

        verify(tripSnapshotService).applyUpsert(eq(EVENT_ID), eq(OCCURRED_AT), payloadCaptor.capture());
        assertEquals("PRIVATE", payloadCaptor.getValue().type());
        assertNull(payloadCaptor.getValue().seatLayout());
        assertNull(payloadCaptor.getValue().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PrivateTripRejected", "RentalAssigned", ""})
    void otherEvent_isIgnored(String eventType) {
        listener.onEvent(message(eventType, Map.of("tripId", TRIP_ID.toString())));

        verifyNoInteractions(tripSnapshotService);
    }

    @Test
    void eventWithoutType_isIgnored() {
        listener.onEvent("{\"eventId\":\"%s\",\"payload\":{}}".formatted(EVENT_ID));

        verifyNoInteractions(tripSnapshotService);
    }

    @Test
    void malformedMessage_throwsSoItGoesToRetryAndDlt() {
        assertThrows(JacksonException.class, () -> listener.onEvent("{no es json"));

        verifyNoInteractions(tripSnapshotService);
    }

    private Map<String, Object> tripPayload(Object ticketPrice) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tripId", TRIP_ID.toString());
        payload.put("type", "REGULAR");
        payload.put("routeId", ROUTE_ID.toString());
        payload.put("originBranchId", ORIGIN_ID.toString());
        payload.put("destinationBranchId", DESTINATION_ID.toString());
        payload.put("originName", "Central");
        payload.put("destinationName", "Xela");
        payload.put("originText", null);
        payload.put("busId", BUS_ID.toString());
        payload.put("busPlate", "P-123ABC");
        payload.put("capacity", 40);
        payload.put("seatLayout", Map.of("rows", 10, "columns", 4, "aisleAfterColumn", 2));
        payload.put("driverId", UUID.randomUUID().toString());
        payload.put("ticketPrice", ticketPrice);
        payload.put("distanceKm", 200.5);
        payload.put("departureAt", DEPARTURE.toString());
        payload.put("estimatedArrival", DEPARTURE.plusHours(4).toString());
        payload.put("status", "SCHEDULED");
        return payload;
    }

    private Map<String, Object> envelope(String eventType) {
        return Map.of(
                "eventId", EVENT_ID.toString(),
                "eventType", eventType,
                "occurredAt", OCCURRED_AT.toString(),
                "aggregateId", TRIP_ID.toString(),
                "version", 1);
    }

    private String message(String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new HashMap<>(envelope(eventType));
        envelope.put("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }
}
