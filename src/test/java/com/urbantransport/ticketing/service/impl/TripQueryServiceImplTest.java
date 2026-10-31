package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.SeatStatus;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.SeatMapResponse;
import com.urbantransport.ticketing.dto.SoldSummaryResponse;
import com.urbantransport.ticketing.dto.TripResponse;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSeatCount;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import com.urbantransport.ticketing.repository.TripSoldSummary;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripQueryServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDateTime NOW = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final LocalDate TOMORROW = NOW.toLocalDate().plusDays(1);
    private static final UUID TRIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TRIP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORIGIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DESTINATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private TripSnapshotRepository tripSnapshotRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Captor
    private ArgumentCaptor<Specification<TripSnapshotEntity>> specCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private TripQueryServiceImpl tripQueryService;

    @BeforeEach
    void setUp() {
        tripQueryService = new TripQueryServiceImpl(tripSnapshotRepository, ticketRepository, CLOCK);
    }

    @Nested
    class SearchAvailable {

        @Mock
        private Root<TripSnapshotEntity> root;

        @Mock
        private CriteriaQuery<?> query;

        @Mock
        private CriteriaBuilder cb;

        @Mock
        private Path<Object> statusPath;

        @Mock
        private Path<Object> originPath;

        @Mock
        private Path<Object> destinationPath;

        @Mock
        private Path<LocalDateTime> departurePath;

        @Mock
        private Predicate predicate;

        @Test
        void trips_includeAvailableSeatsFromActiveTickets() {
            when(tripSnapshotRepository.findAll(ArgumentMatchers.<Specification<TripSnapshotEntity>>any(),
                    any(Pageable.class))).thenReturn(new PageImpl<>(List.of(trip(TRIP_ID, NOW.plusHours(3)),
                    trip(OTHER_TRIP_ID, NOW.plusHours(5))), PageRequest.of(0, 10), 2));
            when(ticketRepository.countSeatsByTripIds(List.of(TRIP_ID, OTHER_TRIP_ID), TicketStatus.ACTIVE))
                    .thenReturn(List.of(new TripSeatCount(TRIP_ID, 15L)));

            PageResponse<TripResponse> page = tripQueryService.searchAvailable(null, null, null, 0, 10);

            assertEquals(2, page.getTotalElements());
            TripResponse first = page.getContent().get(0);
            assertEquals(TRIP_ID, first.getTripId());
            assertEquals(ORIGIN_ID, first.getOriginBranchId());
            assertEquals(DESTINATION_ID, first.getDestinationBranchId());
            assertEquals("Central", first.getOriginName());
            assertEquals("Xela", first.getDestinationName());
            assertEquals("P-123ABC", first.getBusPlate());
            assertEquals(new BigDecimal("50.00"), first.getTicketPrice());
            assertEquals(40, first.getCapacity());
            assertEquals(25, first.getAvailableSeats());
            assertEquals(40, page.getContent().get(1).getAvailableSeats());
        }

        @Test
        void noTrips_doesNotCountSeats() {
            when(tripSnapshotRepository.findAll(ArgumentMatchers.<Specification<TripSnapshotEntity>>any(),
                    any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            PageResponse<TripResponse> page = tripQueryService.searchAvailable(ORIGIN_ID, null, null, 0, 10);

            assertEquals(0, page.getTotalElements());
            verifyNoInteractions(ticketRepository);
        }

        @ParameterizedTest
        @CsvSource({"-1, 0, 0, 1", "3, 200, 3, 50", "0, 10, 0, 10"})
        void pagination_isClampedAndSortedByDeparture(int page, int size, int expectedPage, int expectedSize) {
            when(tripSnapshotRepository.findAll(ArgumentMatchers.<Specification<TripSnapshotEntity>>any(),
                    any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            tripQueryService.searchAvailable(null, null, null, page, size);

            verify(tripSnapshotRepository).findAll(ArgumentMatchers.<Specification<TripSnapshotEntity>>any(),
                    pageableCaptor.capture());
            assertEquals(expectedPage, pageableCaptor.getValue().getPageNumber());
            assertEquals(expectedSize, pageableCaptor.getValue().getPageSize());
            assertEquals(Sort.by("departureAt").ascending(), pageableCaptor.getValue().getSort());
        }

        @Test
        void withoutFilters_onlyRequiresScheduledAndFutureDeparture() {
            stubCriteria();

            buildSpec(null, null, null).toPredicate(root, query, cb);

            verify(cb).equal(statusPath, TripStatus.SCHEDULED);
            verify(cb).greaterThan(departurePath, NOW);
            verify(root, never()).get("originBranchId");
            verify(root, never()).get("destinationBranchId");
            verify(cb, never()).greaterThanOrEqualTo(ArgumentMatchers.<Path<LocalDateTime>>any(),
                    any(LocalDateTime.class));
        }

        @Test
        void withAllFilters_addsBranchesAndWholeDay() {
            stubCriteria();

            buildSpec(ORIGIN_ID, DESTINATION_ID, TOMORROW).toPredicate(root, query, cb);

            verify(cb).equal(statusPath, TripStatus.SCHEDULED);
            verify(cb).greaterThan(departurePath, NOW);
            verify(cb).equal(originPath, ORIGIN_ID);
            verify(cb).equal(destinationPath, DESTINATION_ID);
            verify(cb).greaterThanOrEqualTo(departurePath, TOMORROW.atStartOfDay());
            verify(cb).lessThan(departurePath, TOMORROW.plusDays(1).atStartOfDay());
        }

        private Specification<TripSnapshotEntity> buildSpec(UUID origin, UUID destination, LocalDate date) {
            when(tripSnapshotRepository.findAll(ArgumentMatchers.<Specification<TripSnapshotEntity>>any(),
                    any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
            tripQueryService.searchAvailable(origin, destination, date, 0, 10);
            verify(tripSnapshotRepository).findAll(specCaptor.capture(), any(Pageable.class));
            return specCaptor.getValue();
        }

        private void stubCriteria() {
            lenient().when(root.get("status")).thenReturn(statusPath);
            lenient().when(root.get("originBranchId")).thenReturn(originPath);
            lenient().when(root.get("destinationBranchId")).thenReturn(destinationPath);
            lenient().when(root.<LocalDateTime>get("departureAt")).thenReturn(departurePath);
            lenient().when(cb.equal(any(), any(Object.class))).thenReturn(predicate);
            lenient().when(cb.greaterThan(ArgumentMatchers.<Path<LocalDateTime>>any(), any(LocalDateTime.class)))
                    .thenReturn(predicate);
            lenient().when(cb.greaterThanOrEqualTo(ArgumentMatchers.<Path<LocalDateTime>>any(), any(LocalDateTime.class)))
                    .thenReturn(predicate);
            lenient().when(cb.lessThan(ArgumentMatchers.<Path<LocalDateTime>>any(), any(LocalDateTime.class)))
                    .thenReturn(predicate);
            lenient().when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
        }
    }

    @Nested
    class GetSeatMap {

        @Test
        void sellableTrip_marksOccupiedSeatsAndIsOpen() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TRIP_ID, NOW.plusHours(2))));
            when(ticketRepository.findSeatNumbersByTripIdAndStatusIn(TRIP_ID, TicketStatus.ACTIVE))
                    .thenReturn(List.of(2, 7));

            SeatMapResponse map = tripQueryService.getSeatMap(TRIP_ID);

            assertTrue(map.isOpen());
            assertEquals(10, map.getRows());
            assertEquals(4, map.getColumns());
            assertEquals(2, map.getAisleAfterColumn());
            assertEquals(40, map.getCapacity());
            assertEquals(38, map.getAvailableSeats());
            assertEquals(40, map.getSeats().size());
            assertEquals(SeatStatus.FREE, map.getSeats().get(0).getStatus());
            assertEquals(SeatStatus.OCCUPIED, map.getSeats().get(1).getStatus());
            assertEquals("2C", map.getSeats().get(6).getLabel());
            assertEquals(SeatStatus.OCCUPIED, map.getSeats().get(6).getStatus());
        }

        @Test
        void departedTrip_isShownClosed() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TRIP_ID, NOW.minusMinutes(1))));
            when(ticketRepository.findSeatNumbersByTripIdAndStatusIn(TRIP_ID, TicketStatus.ACTIVE)).thenReturn(List.of());

            assertFalse(tripQueryService.getSeatMap(TRIP_ID).isOpen());
        }

        @Test
        void missingTrip_throwsNotFound() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, "El viaje no existe", () -> tripQueryService.getSeatMap(TRIP_ID));

            verifyNoInteractions(ticketRepository);
        }

        @Test
        void deletedTrip_throwsNotFound() {
            TripSnapshotEntity trip = trip(TRIP_ID, NOW.plusHours(2));
            trip.setStatus(TripStatus.DELETED);
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));

            assertApiError(HttpStatus.NOT_FOUND, "El viaje no existe", () -> tripQueryService.getSeatMap(TRIP_ID));

            verifyNoInteractions(ticketRepository);
        }
    }

    @Nested
    class SoldSummary {

        @Test
        void activeTickets_areCountedWithHighestSeat() {
            when(ticketRepository.summarize(TRIP_ID, TicketStatus.ACTIVE)).thenReturn(new TripSoldSummary(3L, 27));

            SoldSummaryResponse summary = tripQueryService.soldSummary(TRIP_ID);

            assertEquals(TRIP_ID, summary.getTripId());
            assertEquals(3, summary.getActiveTickets());
            assertEquals(27, summary.getMaxSeatNumber());
        }

        @Test
        void withoutTickets_returnsZeroAndNoSeat() {
            when(ticketRepository.summarize(TRIP_ID, TicketStatus.ACTIVE)).thenReturn(new TripSoldSummary(null, null));

            SoldSummaryResponse summary = tripQueryService.soldSummary(TRIP_ID);

            assertEquals(0, summary.getActiveTickets());
            assertNull(summary.getMaxSeatNumber());
        }
    }

    private static TripSnapshotEntity trip(UUID tripId, LocalDateTime departureAt) {
        return TripSnapshotEntity.builder()
                .tripId(tripId)
                .originBranchId(ORIGIN_ID)
                .destinationBranchId(DESTINATION_ID)
                .originName("Central")
                .destinationName("Xela")
                .busPlate("P-123ABC")
                .seatRows(10)
                .seatColumns(4)
                .aisleAfterColumn(2)
                .capacity(40)
                .ticketPrice(new BigDecimal("50.00"))
                .departureAt(departureAt)
                .estimatedArrival(departureAt.plusHours(4))
                .status(TripStatus.SCHEDULED)
                .build();
    }

    private static void assertApiError(HttpStatus status, String message, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(message, exception.getMessage());
    }
}
