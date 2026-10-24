package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.client.CustomerClient;
import com.urbantransport.ticketing.client.CustomerSummary;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.TicketDetailResponse;
import com.urbantransport.ticketing.dto.TripTicketResponse;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.TicketQueryService;
import com.urbantransport.ticketing.util.GenericCustomer;
import com.urbantransport.ticketing.util.NitUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketQueryServiceImpl implements TicketQueryService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<TicketStatus> HISTORY_STATUSES =
            EnumSet.of(TicketStatus.CONFIRMED, TicketStatus.CANCELLED, TicketStatus.REFUNDED);

    private final TicketRepository ticketRepository;
    private final TripSnapshotRepository tripSnapshotRepository;
    private final CustomerClient customerClient;

    @Override
    public PageResponse<TicketDetailResponse> listMine(UUID customerId, TicketStatus status, int page, int size) {
        return history(customerId, status, page, size);
    }

    @Override
    public PageResponse<TicketDetailResponse> listByNit(String rawNit, String accessToken, TicketStatus status,
                                                        int page, int size) {
        String nit = NitUtils.normalize(rawNit);
        if (GenericCustomer.NIT.equals(nit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "El consumidor final (CF) agrupa a personas anónimas: no tiene historial");
        }
        if (!NitUtils.isValid(nit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El NIT no es válido");
        }
        CustomerSummary customer = customerClient.findByNit(nit, accessToken)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "El cliente con NIT " + nit + " no está registrado"));
        return history(customer.id(), status, page, size);
    }

    @Override
    public List<TripTicketResponse> listTripTickets(UUID tripId, UUID branchScope, TicketStatus status) {
        // Un viaje eliminado también se puede consultar: sirve para ver los reembolsos en efectivo pendientes
        TripSnapshotEntity trip = tripSnapshotRepository.findById(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "El viaje no existe"));
        if (branchScope != null && !trip.getOriginBranchId().equals(branchScope)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El viaje no pertenece a tu sucursal");
        }
        Set<TicketStatus> statuses = EnumSet.of(status == null ? TicketStatus.CONFIRMED : status);
        return ticketRepository.findByTripIdAndStatusInOrderBySeatNumber(tripId, statuses).stream()
                .map(TripTicketResponse::fromEntity)
                .toList();
    }

    private PageResponse<TicketDetailResponse> history(UUID customerId, TicketStatus status, int page, int size) {
        Set<TicketStatus> statuses = status == null ? HISTORY_STATUSES : EnumSet.of(status);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("seatNumber")));
        Page<TicketEntity> tickets = ticketRepository.findByCustomerIdAndStatusIn(customerId, statuses, pageable);

        Set<UUID> tripIds = tickets.getContent().stream().map(TicketEntity::getTripId).collect(Collectors.toSet());
        Map<UUID, TripSnapshotEntity> trips = tripSnapshotRepository.findAllById(tripIds).stream()
                .collect(Collectors.toMap(TripSnapshotEntity::getTripId, Function.identity()));

        return PageResponse.of(tickets.map(ticket ->
                TicketDetailResponse.fromEntity(ticket, trips.get(ticket.getTripId()))));
    }
}