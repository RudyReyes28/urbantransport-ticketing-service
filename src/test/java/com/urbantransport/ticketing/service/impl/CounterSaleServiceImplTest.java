package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.client.CustomerClient;
import com.urbantransport.ticketing.client.CustomerSummary;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.dto.CounterSaleRequest;
import com.urbantransport.ticketing.dto.OrderResponse;
import com.urbantransport.ticketing.event.EventTypes;
import com.urbantransport.ticketing.event.TicketPurchasedPayload;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.exception.SeatsUnavailableException;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.service.OrderRules;
import com.urbantransport.ticketing.service.OutboxService;
import com.urbantransport.ticketing.util.GenericCustomer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterSaleServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDateTime NOW = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TRIP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ORDER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CUSTOMER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String TOKEN = "token-del-cajero";
    private static final String NIT = "12345678";
    private static final List<Integer> REQUESTED_SEATS = List.of(6, 5);
    private static final List<Integer> SEATS = List.of(5, 6);

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private CustomerClient customerClient;

    @Mock
    private OutboxService outboxService;

    @Mock
    private OrderRules rules;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Captor
    private ArgumentCaptor<PurchaseOrderEntity> orderCaptor;

    private CounterSaleServiceImpl counterSaleService;

    @BeforeEach
    void setUp() {
        counterSaleService = new CounterSaleServiceImpl(purchaseOrderRepository, customerClient, outboxService, rules,
                transactionTemplate, CLOCK);
    }

    @Nested
    class Sell {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "cf", " C-F "})
        void withoutNitOrWithCf_sellsToGenericCustomerWithoutCallingCustomer(String nit) {
            TripSnapshotEntity trip = trip(BRANCH_ID);
            stubValidations(trip);
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class))).thenAnswer(savedWithId());

            OrderResponse response = counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(nit));

            verify(purchaseOrderRepository).saveAndFlush(orderCaptor.capture());
            PurchaseOrderEntity saved = orderCaptor.getValue();
            assertEquals(GenericCustomer.ID, saved.getCustomerId());
            assertEquals(GenericCustomer.NIT, saved.getCustomerNit());
            assertEquals(GenericCustomer.NIT, saved.getTickets().get(0).getCustomerNit());
            verifyNoInteractions(customerClient);
            assertEquals(GenericCustomer.NIT, response.getCustomerNit());
        }

        @Test
        void registeredNit_savesConfirmedOrderAndRecordsTicketPurchased() {
            TripSnapshotEntity trip = trip(BRANCH_ID);
            stubValidations(trip);
            when(customerClient.findByNit(NIT, TOKEN))
                    .thenReturn(Optional.of(new CustomerSummary(CUSTOMER_ID, NIT, "Ana López", false, true)));
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class))).thenAnswer(savedWithId());

            OrderResponse response = counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(" 1234567-8 "));

            verify(rules).validateSeatsInRange(trip, SEATS);
            verify(rules).assertSeatsFree(TRIP_ID, SEATS);
            verify(purchaseOrderRepository).saveAndFlush(orderCaptor.capture());
            PurchaseOrderEntity saved = orderCaptor.getValue();
            assertEquals(TRIP_ID, saved.getTripId());
            assertEquals(SalesChannel.COUNTER, saved.getChannel());
            assertEquals(CUSTOMER_ID, saved.getCustomerId());
            assertEquals(NIT, saved.getCustomerNit());
            assertNull(saved.getBuyerEmail());
            assertEquals(BRANCH_ID, saved.getBranchId());
            assertEquals(USER_ID, saved.getSoldByUserId());
            assertEquals(new BigDecimal("91.00"), saved.getTotal());
            assertEquals(OrderStatus.CONFIRMED, saved.getStatus());
            assertEquals(TODAY, saved.getPaymentDate());
            assertEquals(NOW, saved.getConfirmedAt());
            assertNull(saved.getExpiresAt());
            assertNull(saved.getIdempotencyKey());
            assertEquals(2, saved.getTickets().size());
            TicketEntity first = saved.getTickets().get(0);
            assertEquals(5, first.getSeatNumber());
            assertEquals("2A", first.getSeatLabel());
            assertEquals("2B", saved.getTickets().get(1).getSeatLabel());
            assertEquals(new BigDecimal("45.50"), first.getPricePaid());
            assertEquals(TicketStatus.CONFIRMED, first.getStatus());
            assertEquals(SalesChannel.COUNTER, first.getChannel());
            assertEquals(BRANCH_ID, first.getBranchId());
            assertEquals(CUSTOMER_ID, first.getCustomerId());
            verify(outboxService).record(EventTypes.AGGREGATE_ORDER, ORDER_ID, EventTypes.TICKET_PURCHASED,
                    TicketPurchasedPayload.fromOrder(saved, trip));
            assertEquals(ORDER_ID, response.getOrderId());
            assertEquals(OrderStatus.CONFIRMED, response.getStatus());
            assertEquals(SalesChannel.COUNTER, response.getChannel());
        }

        @ParameterizedTest
        @ValueSource(strings = {"12AB", "K", "1234567K9"})
        void invalidNit_throwsBadRequestWithoutCallingCustomer(String nit) {
            stubBeforeCustomer(trip(BRANCH_ID));

            assertApiError(HttpStatus.BAD_REQUEST, "El NIT no es válido",
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(nit)));

            verifyNoInteractions(customerClient, transactionTemplate, purchaseOrderRepository, outboxService);
        }

        @Test
        void unregisteredNit_throwsNotFoundWithoutSaving() {
            stubBeforeCustomer(trip(BRANCH_ID));
            when(customerClient.findByNit(NIT, TOKEN)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, "El cliente con NIT 12345678 no está registrado; regístralo primero",
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(NIT)));

            verifyNoInteractions(transactionTemplate, purchaseOrderRepository, outboxService);
        }

        @Test
        void customerUnavailable_propagatesWithoutOpeningTransaction() {
            ApiException unavailable = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de clientes no está disponible, intenta más tarde");
            stubBeforeCustomer(trip(BRANCH_ID));
            when(customerClient.findByNit(NIT, TOKEN)).thenThrow(unavailable);

            ApiException thrown = assertThrows(ApiException.class,
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(NIT)));

            assertSame(unavailable, thrown);
            verifyNoInteractions(transactionTemplate, purchaseOrderRepository, outboxService);
        }

        @Test
        void tripOfAnotherBranch_throwsForbidden() {
            when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
            when(rules.resolvePaymentDate(null)).thenReturn(TODAY);
            when(rules.findTrip(TRIP_ID)).thenReturn(trip(OTHER_BRANCH_ID));

            assertApiError(HttpStatus.FORBIDDEN, "Solo puedes vender viajes que salen de tu sucursal",
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(NIT)));

            verify(rules, never()).requireSellable(any());
            verifyNoInteractions(customerClient, transactionTemplate, purchaseOrderRepository, outboxService);
        }

        @Test
        void tripNotSellable_propagatesConflict() {
            TripSnapshotEntity trip = trip(BRANCH_ID);
            when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
            when(rules.resolvePaymentDate(null)).thenReturn(TODAY);
            when(rules.findTrip(TRIP_ID)).thenReturn(trip);
            doThrow(new ApiException(HttpStatus.CONFLICT, "El viaje ya no está disponible para la venta"))
                    .when(rules).requireSellable(trip);

            assertApiError(HttpStatus.CONFLICT, "El viaje ya no está disponible para la venta",
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(null)));

            verifyNoInteractions(customerClient, transactionTemplate, purchaseOrderRepository, outboxService);
        }

        @Test
        void invalidSeats_throwBeforeLoadingTrip() {
            when(rules.normalizeSeats(REQUESTED_SEATS))
                    .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "Hay asientos repetidos en la petición"));

            assertApiError(HttpStatus.BAD_REQUEST, "Hay asientos repetidos en la petición",
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(null)));

            verify(rules, never()).findTrip(any());
            verifyNoInteractions(customerClient, transactionTemplate, purchaseOrderRepository, outboxService);
        }

        @Test
        void tripMovedToAnotherBranchDuringCustomerLookup_throwsForbiddenWithoutSaving() {
            when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
            when(rules.resolvePaymentDate(null)).thenReturn(TODAY);
            when(rules.findTrip(TRIP_ID)).thenReturn(trip(BRANCH_ID), trip(OTHER_BRANCH_ID));
            runTransactionsInline();

            assertApiError(HttpStatus.FORBIDDEN, "Solo puedes vender viajes que salen de tu sucursal",
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(null)));

            verify(rules, never()).assertSeatsFree(any(), any());
            verifyNoInteractions(purchaseOrderRepository, outboxService);
        }

        @Test
        void seatsTakenInsideTransaction_propagatesSeatsUnavailable() {
            stubBeforeCustomer(trip(BRANCH_ID));
            runTransactionsInline();
            doThrow(new SeatsUnavailableException(List.of(6))).when(rules).assertSeatsFree(TRIP_ID, SEATS);

            SeatsUnavailableException exception = assertThrows(SeatsUnavailableException.class,
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(null)));

            assertEquals(List.of(6), exception.getSeats());
            verifyNoInteractions(purchaseOrderRepository, outboxService);
        }

        @Test
        void integrityViolationOnSeat_throwsSeatsUnavailable() {
            stubValidations(trip(BRANCH_ID));
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_active_seat"));
            when(rules.takenAmong(TRIP_ID, SEATS)).thenReturn(List.of(5));

            SeatsUnavailableException exception = assertThrows(SeatsUnavailableException.class,
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(null)));

            assertEquals(List.of(5), exception.getSeats());
            verifyNoInteractions(outboxService);
        }

        @Test
        void integrityViolationWithoutTakenSeats_rethrowsSameException() {
            DataIntegrityViolationException violation = new DataIntegrityViolationException("ck_orders_nit_format");
            stubValidations(trip(BRANCH_ID));
            when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrderEntity.class))).thenThrow(violation);
            when(rules.takenAmong(TRIP_ID, SEATS)).thenReturn(List.of());

            DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                    () -> counterSaleService.sell(USER_ID, BRANCH_ID, TOKEN, request(null)));

            assertSame(violation, thrown);
            verifyNoInteractions(outboxService);
        }
    }

    private void stubBeforeCustomer(TripSnapshotEntity trip) {
        when(rules.normalizeSeats(REQUESTED_SEATS)).thenReturn(SEATS);
        when(rules.resolvePaymentDate(null)).thenReturn(TODAY);
        when(rules.findTrip(TRIP_ID)).thenReturn(trip);
    }

    private void stubValidations(TripSnapshotEntity trip) {
        stubBeforeCustomer(trip);
        runTransactionsInline();
    }

    private void runTransactionsInline() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private static Answer<PurchaseOrderEntity> savedWithId() {
        return inv -> {
            PurchaseOrderEntity order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return order;
        };
    }

    private static CounterSaleRequest request(String nit) {
        return new CounterSaleRequest(TRIP_ID, REQUESTED_SEATS, nit, null);
    }

    private static TripSnapshotEntity trip(UUID originBranchId) {
        return TripSnapshotEntity.builder()
                .tripId(TRIP_ID)
                .originBranchId(originBranchId)
                .originName("Central")
                .destinationName("Xela")
                .busPlate("P-123ABC")
                .seatRows(10)
                .seatColumns(4)
                .aisleAfterColumn(2)
                .capacity(40)
                .ticketPrice(new BigDecimal("45.5"))
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
