package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.client.CustomerClient;
import com.urbantransport.ticketing.client.CustomerSummary;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.TicketDetailResponse;
import com.urbantransport.ticketing.dto.TripTicketResponse;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketQueryServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.now(ZoneId.of("America/Guatemala"));
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRIP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_TRIP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BRANCH_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID TICKET_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID ORDER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String TOKEN = "token-del-personal";
    private static final Set<TicketStatus> HISTORY =
            EnumSet.of(TicketStatus.CONFIRMED, TicketStatus.CANCELLED, TicketStatus.REFUNDED);
    private static final Sort HISTORY_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("seatNumber"));

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private TripSnapshotRepository tripSnapshotRepository;

    @Mock
    private CustomerClient customerClient;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private TicketQueryServiceImpl ticketQueryService;

    @BeforeEach
    void setUp() {
        ticketQueryService = new TicketQueryServiceImpl(ticketRepository, tripSnapshotRepository, customerClient);
    }

    @Nested
    class ListMine {

        @Test
        void withoutStatus_returnsHistoryWithTripData() {
            TicketEntity first = ticket(TRIP_ID, 3, TicketStatus.CONFIRMED);
            TicketEntity second = ticket(OTHER_TRIP_ID, 1, TicketStatus.REFUNDED);
            when(ticketRepository.findByCustomerIdAndStatusIn(eq(CUSTOMER_ID), eq(HISTORY), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 10), 2));
            when(tripSnapshotRepository.findAllById(Set.of(TRIP_ID, OTHER_TRIP_ID)))
                    .thenReturn(List.of(trip(TRIP_ID, BRANCH_ID, "Xela"), trip(OTHER_TRIP_ID, BRANCH_ID, "Peten")));

            PageResponse<TicketDetailResponse> page = ticketQueryService.listMine(CUSTOMER_ID, null, 0, 10);

            assertEquals(2, page.getTotalElements());
            TicketDetailResponse detail = page.getContent().get(0);
            assertEquals(TICKET_ID, detail.getTicketId());
            assertEquals(ORDER_ID, detail.getOrderId());
            assertEquals(3, detail.getSeatNumber());
            assertEquals(TicketStatus.CONFIRMED, detail.getStatus());
            assertEquals("Xela", detail.getDestinationName());
            assertEquals("P-123ABC", detail.getBusPlate());
            assertEquals(TripStatus.SCHEDULED, detail.getTripStatus());
            assertEquals("Peten", page.getContent().get(1).getDestinationName());
        }

        @ParameterizedTest
        @EnumSource(TicketStatus.class)
        void withStatus_filtersByThatStatusOnly(TicketStatus status) {
            when(ticketRepository.findByCustomerIdAndStatusIn(eq(CUSTOMER_ID), eq(EnumSet.of(status)),
                    any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
            when(tripSnapshotRepository.findAllById(Set.of())).thenReturn(List.of());

            PageResponse<TicketDetailResponse> page = ticketQueryService.listMine(CUSTOMER_ID, status, 0, 10);

            assertEquals(0, page.getTotalElements());
        }

        @ParameterizedTest
        @CsvSource({"-3, 0, 0, 1", "2, 500, 2, 50", "1, 20, 1, 20"})
        void pagination_isClampedAndSortedByNewest(int page, int size, int expectedPage, int expectedSize) {
            when(ticketRepository.findByCustomerIdAndStatusIn(eq(CUSTOMER_ID), eq(HISTORY), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            when(tripSnapshotRepository.findAllById(Set.of())).thenReturn(List.of());

            ticketQueryService.listMine(CUSTOMER_ID, null, page, size);

            verify(ticketRepository).findByCustomerIdAndStatusIn(eq(CUSTOMER_ID), eq(HISTORY), pageableCaptor.capture());
            assertEquals(expectedPage, pageableCaptor.getValue().getPageNumber());
            assertEquals(expectedSize, pageableCaptor.getValue().getPageSize());
            assertEquals(HISTORY_SORT, pageableCaptor.getValue().getSort());
        }
    }

    @Nested
    class ListByNit {

        @Test
        void registeredNit_returnsHistoryOfThatCustomer() {
            when(customerClient.findByNit("12345678", TOKEN))
                    .thenReturn(Optional.of(new CustomerSummary(CUSTOMER_ID, "12345678", "Ana", false, true)));
            when(ticketRepository.findByCustomerIdAndStatusIn(eq(CUSTOMER_ID), eq(HISTORY), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ticket(TRIP_ID, 1, TicketStatus.CANCELLED))));
            when(tripSnapshotRepository.findAllById(Set.of(TRIP_ID))).thenReturn(List.of(trip(TRIP_ID, BRANCH_ID, "Xela")));

            PageResponse<TicketDetailResponse> page = ticketQueryService.listByNit(" 1234567-8 ", TOKEN, null, 0, 10);

            assertEquals(1, page.getContent().size());
            assertEquals(TicketStatus.CANCELLED, page.getContent().get(0).getStatus());
        }

        @ParameterizedTest
        @ValueSource(strings = {"CF", "cf", " c-f "})
        void genericConsumer_throwsBadRequestWithoutCallingCustomer(String nit) {
            assertApiError(HttpStatus.BAD_REQUEST,
                    "El consumidor final (CF) agrupa a personas anónimas: no tiene historial",
                    () -> ticketQueryService.listByNit(nit, TOKEN, null, 0, 10));

            verifyNoInteractions(customerClient, ticketRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"12AB", "K", "abc"})
        void invalidNit_throwsBadRequestWithoutCallingCustomer(String nit) {
            assertApiError(HttpStatus.BAD_REQUEST, "El NIT no es válido",
                    () -> ticketQueryService.listByNit(nit, TOKEN, null, 0, 10));

            verifyNoInteractions(customerClient, ticketRepository);
        }

        @Test
        void unregisteredNit_throwsNotFound() {
            when(customerClient.findByNit("12345678", TOKEN)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, "El cliente con NIT 12345678 no está registrado",
                    () -> ticketQueryService.listByNit("12345678", TOKEN, null, 0, 10));

            verifyNoInteractions(ticketRepository);
        }
    }

    @Nested
    class ListTripTickets {

        @Test
        void withoutStatus_returnsConfirmedTicketsOfOwnBranch() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TRIP_ID, BRANCH_ID, "Xela")));
            when(ticketRepository.findByTripIdAndStatusInOrderBySeatNumber(TRIP_ID, EnumSet.of(TicketStatus.CONFIRMED)))
                    .thenReturn(List.of(ticket(TRIP_ID, 4, TicketStatus.CONFIRMED)));

            List<TripTicketResponse> tickets = ticketQueryService.listTripTickets(TRIP_ID, BRANCH_ID, null);

            assertEquals(1, tickets.size());
            TripTicketResponse ticket = tickets.get(0);
            assertEquals(TICKET_ID, ticket.getTicketId());
            assertEquals(ORDER_ID, ticket.getOrderId());
            assertEquals(4, ticket.getSeatNumber());
            assertEquals("12345678", ticket.getCustomerNit());
            assertEquals(SalesChannel.ONLINE, ticket.getChannel());
        }

        @Test
        void systemAdminWithStatus_seesAnyBranchEvenDeletedTrip() {
            TripSnapshotEntity deleted = trip(TRIP_ID, OTHER_BRANCH_ID, "Xela");
            deleted.setStatus(TripStatus.DELETED);
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(deleted));
            when(ticketRepository.findByTripIdAndStatusInOrderBySeatNumber(TRIP_ID, EnumSet.of(TicketStatus.CANCELLED)))
                    .thenReturn(List.of(ticket(TRIP_ID, 1, TicketStatus.CANCELLED)));

            List<TripTicketResponse> tickets = ticketQueryService.listTripTickets(TRIP_ID, null, TicketStatus.CANCELLED);

            assertEquals(TicketStatus.CANCELLED, tickets.get(0).getStatus());
        }

        @Test
        void tripOfAnotherBranch_throwsForbidden() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip(TRIP_ID, OTHER_BRANCH_ID, "Xela")));

            assertApiError(HttpStatus.FORBIDDEN, "El viaje no pertenece a tu sucursal",
                    () -> ticketQueryService.listTripTickets(TRIP_ID, BRANCH_ID, null));

            verifyNoInteractions(ticketRepository);
        }

        @Test
        void missingTrip_throwsNotFound() {
            when(tripSnapshotRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, "El viaje no existe",
                    () -> ticketQueryService.listTripTickets(TRIP_ID, null, null));

            verifyNoInteractions(ticketRepository);
        }
    }

    private static TicketEntity ticket(UUID tripId, int seat, TicketStatus status) {
        return TicketEntity.builder()
                .id(TICKET_ID)
                .orderId(ORDER_ID)
                .tripId(tripId)
                .seatNumber(seat)
                .seatLabel("1A")
                .customerId(CUSTOMER_ID)
                .customerNit("12345678")
                .pricePaid(new BigDecimal("50.00"))
                .channel(SalesChannel.ONLINE)
                .status(status)
                .createdAt(NOW)
                .build();
    }

    private static TripSnapshotEntity trip(UUID tripId, UUID originBranchId, String destination) {
        return TripSnapshotEntity.builder()
                .tripId(tripId)
                .originBranchId(originBranchId)
                .originName("Central")
                .destinationName(destination)
                .busPlate("P-123ABC")
                .departureAt(NOW.plusDays(1))
                .status(TripStatus.SCHEDULED)
                .build();
    }

    private static void assertApiError(HttpStatus status, String message, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(message, exception.getMessage());
    }
}
