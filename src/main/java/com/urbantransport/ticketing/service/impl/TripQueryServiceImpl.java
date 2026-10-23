package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.SeatMapResponse;
import com.urbantransport.ticketing.dto.TripResponse;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSeatCount;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.service.TripQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripQueryServiceImpl implements TripQueryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final TripSnapshotRepository tripSnapshotRepository;
    private final TicketRepository ticketRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TripResponse> searchAvailable(UUID originBranchId, UUID destinationBranchId, LocalDate date,
                                                      int page, int size) {
        LocalDateTime now = LocalDateTime.now(clock);

        Specification<TripSnapshotEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), TripStatus.SCHEDULED),
                cb.greaterThan(root.<LocalDateTime>get("departureAt"), now));
        if (originBranchId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("originBranchId"), originBranchId));
        }
        if (destinationBranchId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("destinationBranchId"), destinationBranchId));
        }
        if (date != null) {
            LocalDateTime from = date.atStartOfDay();
            LocalDateTime to = date.plusDays(1).atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.and(
                    cb.greaterThanOrEqualTo(root.<LocalDateTime>get("departureAt"), from),
                    cb.lessThan(root.<LocalDateTime>get("departureAt"), to)));
        }

        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by("departureAt").ascending());
        Page<TripSnapshotEntity> trips = tripSnapshotRepository.findAll(spec, pageable);

        Map<UUID, Long> taken = Map.of();
        if (!trips.isEmpty()) {
            List<UUID> ids = trips.getContent().stream().map(TripSnapshotEntity::getTripId).toList();
            taken = ticketRepository.countSeatsByTripIds(ids, TicketStatus.ACTIVE).stream()
                    .collect(Collectors.toMap(TripSeatCount::tripId, TripSeatCount::taken));
        }
        Map<UUID, Long> takenByTrip = taken;
        return PageResponse.of(trips.map(trip -> TripResponse.fromEntity(trip,
                trip.getCapacity() - takenByTrip.getOrDefault(trip.getTripId(), 0L).intValue())));
    }

    @Override
    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(UUID tripId) {
        TripSnapshotEntity trip = tripSnapshotRepository.findById(tripId)
                .filter(found -> found.getStatus() != TripStatus.DELETED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "El viaje no existe"));

        Set<Integer> occupied = new HashSet<>(
                ticketRepository.findSeatNumbersByTripIdAndStatusIn(tripId, TicketStatus.ACTIVE));
        return SeatMapResponse.fromEntity(trip, occupied, trip.isSellable(LocalDateTime.now(clock)));
    }
}