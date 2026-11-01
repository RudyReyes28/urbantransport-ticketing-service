package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.event.TripUpsertEvent;

import java.time.Instant;
import java.util.UUID;

public interface TripSnapshotService {

    /** TripScheduled / TripUpdated: crea o actualiza la copia local del viaje. */
    void applyUpsert(UUID eventId, Instant occurredAt, TripUpsertEvent.TripPayload payload);

    /** TripStarted / TripCompleted / TripDeleted: mueve el estado del viaje hacia adelante. */
    void applyStatusChange(UUID eventId, Instant occurredAt, UUID tripId, TripStatus newStatus);
}