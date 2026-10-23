package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.service.TripSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class TripEventsListener {

    private final JsonMapper jsonMapper;
    private final TripSnapshotService tripSnapshotService;

    @KafkaListener(topics = "${ticketing.kafka.trip-events-topic}")
    public void onEvent(String message) {
        EventHeader header = jsonMapper.readValue(message, EventHeader.class);
        String type = header.eventType() == null ? "" : header.eventType();

        switch (type) {
            case EventTypes.TRIP_SCHEDULED, EventTypes.TRIP_UPDATED -> {
                TripUpsertEvent event = jsonMapper.readValue(message, TripUpsertEvent.class);
                tripSnapshotService.applyUpsert(event.eventId(), event.occurredAt(), event.payload());
            }
            case EventTypes.TRIP_STARTED -> applyStatus(message, TripStatus.IN_TRANSIT);
            case EventTypes.TRIP_COMPLETED -> applyStatus(message, TripStatus.COMPLETED);
            case EventTypes.TRIP_DELETED -> applyStatus(message, TripStatus.DELETED);
            default -> log.debug("Evento {} ignorado", type);
        }
    }

    private void applyStatus(String message, TripStatus newStatus) {
        TripStatusEvent event = jsonMapper.readValue(message, TripStatusEvent.class);
        tripSnapshotService.applyStatusChange(event.eventId(), event.occurredAt(),
                event.payload() == null ? null : event.payload().tripId(), newStatus);
    }
}