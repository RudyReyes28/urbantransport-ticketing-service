package com.urbantransport.ticketing.event;

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

    @KafkaListener(topics = "${ticketing.kafka.trip-events-topic}")
    public void onEvent(String message) {
        EventHeader header = jsonMapper.readValue(message, EventHeader.class);
        String type = header.eventType() == null ? "" : header.eventType();

        switch (type) {
            case EventTypes.TRIP_SCHEDULED, EventTypes.TRIP_UPDATED, EventTypes.TRIP_DELETED,
                 EventTypes.TRIP_STARTED, EventTypes.TRIP_COMPLETED ->
                    log.info("Evento de viaje recibido: {} ({})", type, header.eventId());
            default -> log.debug("Evento {} ignorado", type);
        }
    }
}