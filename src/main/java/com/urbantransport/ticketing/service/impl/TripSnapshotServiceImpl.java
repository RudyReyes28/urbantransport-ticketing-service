package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.ProcessedEventEntity;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.event.TripUpsertEvent;
import com.urbantransport.ticketing.repository.ProcessedEventRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.TripCascadeService;
import com.urbantransport.ticketing.service.TripSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplica los eventos de trip-service a la copia local de viajes. Cambio, deduplicación y marca del eventId van en
 * una sola transacción. Los errores de datos lanzan excepción para que el mensaje vaya al DLT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripSnapshotServiceImpl implements TripSnapshotService {

    private static final String TYPE_REGULAR = "REGULAR";
    private static final int MAX_SEAT_COLUMNS = 6;

    private final TripSnapshotRepository tripSnapshotRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TripCascadeService tripCascadeService;

    @Override
    @Transactional
    public void applyUpsert(UUID eventId, Instant occurredAt, TripUpsertEvent.TripPayload payload) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        if (payload == null || payload.tripId() == null || occurredAt == null) {
            throw new IllegalArgumentException("Evento de viaje sin tripId u occurredAt");
        }
        if (payload.type() == null) {
            throw new IllegalArgumentException("Evento de viaje " + payload.tripId() + " sin type");
        }
        if (!TYPE_REGULAR.equals(payload.type())) {
            log.debug("Viaje {} de tipo {} ignorado: no se vende por boletos", payload.tripId(), payload.type());
            markProcessed(eventId);
            return;
        }
        validate(payload);

        Optional<TripSnapshotEntity> existing = tripSnapshotRepository.findById(payload.tripId());
        if (existing.isEmpty()) {
            tripSnapshotRepository.save(create(payload, occurredAt));
        } else {
            TripSnapshotEntity trip = existing.get();
            if (trip.getStatus() == TripStatus.DELETED || !occurredAt.isAfter(trip.getLastEventAt())) {
                log.debug("Evento {} del viaje {} descartado (viaje eliminado o evento más viejo)",
                        eventId, payload.tripId());
            } else {
                update(trip, payload, occurredAt);
                tripSnapshotRepository.save(trip);
            }
        }
        markProcessed(eventId);
    }

    @Override
    @Transactional
    public void applyStatusChange(UUID eventId, Instant occurredAt, UUID tripId, TripStatus newStatus) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        if (tripId == null || occurredAt == null) {
            throw new IllegalArgumentException("Evento de estado de viaje sin tripId u occurredAt");
        }

        Optional<TripSnapshotEntity> existing = tripSnapshotRepository.findById(tripId);
        if (existing.isEmpty()) {
            // Viaje privado o que nunca llegó a ticketing: no hay nada que actualizar
            log.debug("Viaje {} desconocido, evento {} ignorado", tripId, eventId);
            markProcessed(eventId);
            return;
        }

        TripSnapshotEntity trip = existing.get();
        if (!occurredAt.isAfter(trip.getLastEventAt()) || !canMove(trip.getStatus(), newStatus)) {
            log.debug("Cambio {} -> {} del viaje {} descartado", trip.getStatus(), newStatus, tripId);
        } else {
            trip.setStatus(newStatus);
            trip.setLastEventAt(occurredAt);
            tripSnapshotRepository.save(trip);
            switch (newStatus) {
                // Si el viaje sale (o se completa sin haber pasado por TripStarted), nada pendiente puede cobrarse ya
                case IN_TRANSIT, COMPLETED -> tripCascadeService.expirePendingOrders(tripId);
                case DELETED -> tripCascadeService.cancelTripOrders(tripId);
                default -> { }
            }
        }
        markProcessed(eventId);
    }

    // ---------- reglas ----------

    /** Los estados solo avanzan; DELETED es terminal. */
    private boolean canMove(TripStatus from, TripStatus to) {
        return switch (to) {
            case SCHEDULED -> false;
            case IN_TRANSIT -> from == TripStatus.SCHEDULED;
            case COMPLETED, DELETED -> from == TripStatus.SCHEDULED || from == TripStatus.IN_TRANSIT;
        };
    }

    private void validate(TripUpsertEvent.TripPayload payload) {
        require(payload.routeId(), "routeId");
        require(payload.originBranchId(), "originBranchId");
        require(payload.destinationBranchId(), "destinationBranchId");
        require(payload.originName(), "originName");
        require(payload.destinationName(), "destinationName");
        require(payload.busId(), "busId");
        require(payload.busPlate(), "busPlate");
        require(payload.ticketPrice(), "ticketPrice");
        require(payload.departureAt(), "departureAt");
        require(payload.seatLayout(), "seatLayout");

        TripUpsertEvent.SeatLayoutPayload layout = payload.seatLayout();
        require(layout.rows(), "seatLayout.rows");
        require(layout.columns(), "seatLayout.columns");
        require(layout.aisleAfterColumn(), "seatLayout.aisleAfterColumn");

        if (layout.rows() < 1 || layout.columns() < 1 || layout.columns() > MAX_SEAT_COLUMNS
                || layout.aisleAfterColumn() < 0 || layout.aisleAfterColumn() >= layout.columns()) {
            throw new IllegalArgumentException("Distribución de asientos inválida en el viaje " + payload.tripId());
        }
        if (payload.capacity() != null && payload.capacity() != layout.rows() * layout.columns()) {
            throw new IllegalArgumentException("capacity no coincide con filas x columnas en el viaje " + payload.tripId());
        }
        if (payload.ticketPrice().signum() < 0) {
            throw new IllegalArgumentException("ticketPrice negativo en el viaje " + payload.tripId());
        }
        if (payload.status() == TripStatus.DELETED) {
            throw new IllegalArgumentException("La eliminación de un viaje llega con TripDeleted, no con " + payload.tripId());
        }
    }

    private void require(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException("Evento de viaje sin " + field);
        }
    }

    private TripSnapshotEntity create(TripUpsertEvent.TripPayload payload, Instant occurredAt) {
        TripSnapshotEntity trip = TripSnapshotEntity.builder()
                .tripId(payload.tripId())
                .status(payload.status() != null ? payload.status() : TripStatus.SCHEDULED)
                .build();
        copyData(trip, payload);
        trip.setLastEventAt(occurredAt);
        return trip;
    }

    private void update(TripSnapshotEntity trip, TripUpsertEvent.TripPayload payload, Instant occurredAt) {
        copyData(trip, payload);
        // El estado nunca retrocede: un TripUpdated tardío no revive un viaje ya iniciado
        if (payload.status() != null && payload.status() != trip.getStatus()
                && canMove(trip.getStatus(), payload.status())) {
            trip.setStatus(payload.status());
        }
        trip.setLastEventAt(occurredAt);
    }

    private void copyData(TripSnapshotEntity trip, TripUpsertEvent.TripPayload payload) {
        TripUpsertEvent.SeatLayoutPayload layout = payload.seatLayout();
        trip.setRouteId(payload.routeId());
        trip.setOriginBranchId(payload.originBranchId());
        trip.setDestinationBranchId(payload.destinationBranchId());
        trip.setOriginName(payload.originName());
        trip.setDestinationName(payload.destinationName());
        trip.setBusId(payload.busId());
        trip.setBusPlate(payload.busPlate());
        trip.setSeatRows(layout.rows());
        trip.setSeatColumns(layout.columns());
        trip.setAisleAfterColumn(layout.aisleAfterColumn());
        trip.setCapacity(layout.rows() * layout.columns());
        trip.setTicketPrice(payload.ticketPrice());
        trip.setDepartureAt(payload.departureAt());
        trip.setEstimatedArrival(payload.estimatedArrival());
    }

    private void markProcessed(UUID eventId) {
        processedEventRepository.save(ProcessedEventEntity.builder().eventId(eventId).build());
    }
}