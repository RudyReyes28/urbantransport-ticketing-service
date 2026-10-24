package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.TicketDetailResponse;
import com.urbantransport.ticketing.dto.TripTicketResponse;

import java.util.List;
import java.util.UUID;

public interface TicketQueryService {

    /** Historial del cliente. Sin status: confirmados, cancelados y reembolsados. */
    PageResponse<TicketDetailResponse> listMine(UUID customerId, TicketStatus status, int page, int size);

    /** Historial de un cliente registrado, buscado por NIT con el token del personal. */
    PageResponse<TicketDetailResponse> listByNit(String nit, String accessToken, TicketStatus status,
                                                 int page, int size);

    /**
     * Boletos de un viaje, por asiento. Sin status: confirmados.
     *
     * @param branchScope sucursal a la que se limita la consulta; null para ver cualquier viaje
     */
    List<TripTicketResponse> listTripTickets(UUID tripId, UUID branchScope, TicketStatus status);
}