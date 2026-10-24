package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.config.TicketingProperties;
import com.urbantransport.ticketing.domain.SeatLayout;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.exception.SeatsUnavailableException;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Validaciones comunes a la venta en mostrador y a la compra en línea. */
@Component
@RequiredArgsConstructor
public class OrderRules {

    private final TripSnapshotRepository tripSnapshotRepository;
    private final TicketRepository ticketRepository;
    private final TicketingProperties properties;
    private final Clock clock;

    /** Sin repetidos y dentro del máximo por orden; devuelve los asientos ordenados. */
    public List<Integer> normalizeSeats(List<Integer> requested) {
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Hay asientos repetidos en la petición");
        }
        if (requested.size() > properties.maxSeatsPerOrder()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Se pueden comprar hasta " + properties.maxSeatsPerOrder() + " asientos por orden");
        }
        return requested.stream().sorted().toList();
    }

    /** Hoy (hora de Guatemala) si no viene; no puede ser futura. */
    public LocalDate resolvePaymentDate(LocalDate requested) {
        LocalDate today = LocalDate.now(clock);
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La fecha de pago no puede ser futura");
        }
        return requested;
    }

    /** Un viaje eliminado cuenta como inexistente. */
    public TripSnapshotEntity findTrip(UUID tripId) {
        return tripSnapshotRepository.findById(tripId)
                .filter(found -> found.getStatus() != TripStatus.DELETED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "El viaje no existe"));
    }

    public void requireSellable(TripSnapshotEntity trip) {
        if (!trip.isSellable(LocalDateTime.now(clock))) {
            throw new ApiException(HttpStatus.CONFLICT, "El viaje ya no está disponible para la venta");
        }
    }

    public void validateSeatsInRange(TripSnapshotEntity trip, List<Integer> seats) {
        SeatLayout layout = trip.seatLayout();
        List<Integer> invalid = seats.stream().filter(seat -> !layout.contains(seat)).toList();
        if (!invalid.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Asientos fuera de rango (1 a " + layout.capacity() + "): " + invalid);
        }
    }

    /** De los asientos pedidos, los que ya están ocupados (pendientes o confirmados). */
    public List<Integer> takenAmong(UUID tripId, List<Integer> seats) {
        return ticketRepository.findSeatNumbersByTripIdAndStatusIn(tripId, TicketStatus.ACTIVE).stream()
                .filter(seats::contains)
                .sorted()
                .toList();
    }

    public void assertSeatsFree(UUID tripId, List<Integer> seats) {
        List<Integer> taken = takenAmong(tripId, seats);
        if (!taken.isEmpty()) {
            throw new SeatsUnavailableException(taken);
        }
    }
}