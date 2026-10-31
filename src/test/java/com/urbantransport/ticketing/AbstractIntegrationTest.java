package com.urbantransport.ticketing;

import com.jayway.jsonpath.JsonPath;
import com.urbantransport.ticketing.client.CustomerClient;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.SalesChannel;
import com.urbantransport.ticketing.domain.SeatLayout;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.repository.TicketRepository;
import com.urbantransport.ticketing.repository.TripSnapshotRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties = "ticketing.expiration-interval-ms=3600000")
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class AbstractIntegrationTest {

    protected static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    protected static final UUID USER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    protected static final BigDecimal PRICE = new BigDecimal("50.00");

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TripSnapshotRepository tripSnapshotRepository;

    @Autowired
    protected PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    protected TicketRepository ticketRepository;

    @MockitoBean
    protected CustomerClient customerClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE tickets, purchase_orders, trip_snapshot, outbox_events, processed_events CASCADE");
    }

    protected static RequestPostProcessor customer(UUID customerId, String nit) {
        return jwt()
                .jwt(token -> token.subject(USER_ID.toString())
                        .claim("roles", List.of("CUSTOMER"))
                        .claim("customerId", customerId.toString())
                        .claim("nit", nit)
                        .claim("email", "cliente@mail.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    protected static RequestPostProcessor branchAdmin(UUID branchId) {
        return userWithRole("BRANCH_ADMIN", branchId);
    }

    protected static RequestPostProcessor cashier(UUID branchId) {
        return userWithRole("CASHIER", branchId);
    }

    protected static RequestPostProcessor systemAdmin() {
        return userWithRole("SYSTEM_ADMIN", null);
    }

    protected static RequestPostProcessor userWithRole(String role, UUID branchId) {
        return jwt()
                .jwt(token -> {
                    token.subject(USER_ID.toString()).claim("roles", List.of(role));
                    if (branchId != null) {
                        token.claim("branchId", branchId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    protected static LocalDateTime now() {
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    protected static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    protected static UUID idAt(MvcResult result, String path) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), path));
    }

    protected TripSnapshotEntity saveTrip(UUID originBranchId, UUID destinationBranchId, LocalDateTime departureAt,
                                          TripStatus status) {
        return tripSnapshotRepository.save(TripSnapshotEntity.builder()
                .tripId(UUID.randomUUID())
                .routeId(UUID.randomUUID())
                .originBranchId(originBranchId)
                .destinationBranchId(destinationBranchId)
                .originName("Central")
                .destinationName("Xela")
                .busId(UUID.randomUUID())
                .busPlate("P-123ABC")
                .capacity(40)
                .seatRows(10)
                .seatColumns(4)
                .aisleAfterColumn(2)
                .ticketPrice(PRICE)
                .departureAt(departureAt)
                .estimatedArrival(departureAt.plusHours(4))
                .status(status)
                .lastEventAt(Instant.now().minusSeconds(3600))
                .build());
    }

    protected PurchaseOrderEntity saveOnlineOrder(TripSnapshotEntity trip, UUID customerId, OrderStatus status,
                                                  TicketStatus ticketStatus, LocalDateTime expiresAt,
                                                  Integer... seats) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .tripId(trip.getTripId())
                .channel(SalesChannel.ONLINE)
                .customerId(customerId)
                .customerNit("12345678")
                .buyerEmail("cliente@mail.com")
                .branchId(trip.getOriginBranchId())
                .total(PRICE.multiply(BigDecimal.valueOf(seats.length)))
                .status(status)
                .paymentDate(today())
                .expiresAt(expiresAt)
                .confirmedAt(status == OrderStatus.CONFIRMED ? now() : null)
                .build();
        addTickets(order, trip, ticketStatus, seats);
        return purchaseOrderRepository.save(order);
    }

    protected PurchaseOrderEntity saveCounterOrder(TripSnapshotEntity trip, UUID customerId, String nit,
                                                   Integer... seats) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .tripId(trip.getTripId())
                .channel(SalesChannel.COUNTER)
                .customerId(customerId)
                .customerNit(nit)
                .branchId(trip.getOriginBranchId())
                .soldByUserId(USER_ID)
                .total(PRICE.multiply(BigDecimal.valueOf(seats.length)))
                .status(OrderStatus.CONFIRMED)
                .paymentDate(today())
                .confirmedAt(now())
                .build();
        addTickets(order, trip, TicketStatus.CONFIRMED, seats);
        return purchaseOrderRepository.save(order);
    }

    private static void addTickets(PurchaseOrderEntity order, TripSnapshotEntity trip, TicketStatus status,
                                   Integer... seats) {
        SeatLayout layout = trip.seatLayout();
        for (int seat : seats) {
            order.addTicket(TicketEntity.builder()
                    .tripId(trip.getTripId())
                    .seatNumber(seat)
                    .seatLabel(layout.labelOf(seat))
                    .customerId(order.getCustomerId())
                    .customerNit(order.getCustomerNit())
                    .pricePaid(PRICE)
                    .channel(order.getChannel())
                    .branchId(order.getBranchId())
                    .status(status)
                    .cancelReason(status == TicketStatus.CANCELLED || status == TicketStatus.REFUNDED
                            ? "TRIP_DELETED" : null)
                    .build());
        }
    }

    protected List<String> outboxEventTypes(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at, event_type",
                String.class, aggregateId);
    }

    protected int count(String sql, Object... args) {
        Integer total = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return total == null ? 0 : total;
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                }
            }));
            return postgres;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
