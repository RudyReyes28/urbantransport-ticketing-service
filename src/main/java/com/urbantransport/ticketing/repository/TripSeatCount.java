package com.urbantransport.ticketing.repository;

import java.util.UUID;

/** Asientos ocupados de un viaje (resultado de TicketRepository.countActiveSeatsByTripIds). */
public record TripSeatCount(UUID tripId, Long taken) {
}