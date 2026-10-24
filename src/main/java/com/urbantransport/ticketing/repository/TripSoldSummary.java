package com.urbantransport.ticketing.repository;

/** Boletos activos de un viaje y el número de asiento más alto vendido (null si no hay). */
public record TripSoldSummary(Long activeTickets, Integer maxSeatNumber) {
}