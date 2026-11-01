package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.AbstractIntegrationTest;
import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import com.urbantransport.ticketing.domain.TripStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KafkaEventsIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORIGIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DESTINATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${ticketing.kafka.trip-events-topic}")
    private String tripTopic;

    @Value("${ticketing.kafka.payment-events-topic}")
    private String paymentTopic;

    @Value("${ticketing.kafka.ticket-events-topic}")
    private String ticketTopic;

    @Test
    void tripScheduledAndUpdated_buildSnapshotIgnoringDuplicatedEvent() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID scheduledEventId = UUID.randomUUID();
        Instant base = Instant.now();
        LocalDateTime departure = now().plusDays(3);

        send(tripTopic, tripId, envelope(scheduledEventId, EventTypes.TRIP_SCHEDULED, tripId, base,
                tripPayload(tripId, "REGULAR", departure, 50)));
        send(tripTopic, tripId, envelope(scheduledEventId, EventTypes.TRIP_SCHEDULED, tripId, base.plusSeconds(2),
                tripPayload(tripId, "REGULAR", departure, 99)));
        send(tripTopic, tripId, envelope(UUID.randomUUID(), EventTypes.TRIP_UPDATED, tripId, base.plusSeconds(1),
                tripPayload(tripId, "REGULAR", departure, 60)));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Optional<TripSnapshotEntity> trip = tripSnapshotRepository.findById(tripId);
            assertTrue(trip.isPresent());
            assertEquals(0, new BigDecimal("60.00").compareTo(trip.get().getTicketPrice()));
        });
        TripSnapshotEntity trip = tripSnapshotRepository.findById(tripId).orElseThrow();
        assertEquals(TripStatus.SCHEDULED, trip.getStatus());
        assertEquals(ORIGIN_ID, trip.getOriginBranchId());
        assertEquals("Central", trip.getOriginName());
        assertEquals(10, trip.getSeatRows());
        assertEquals(40, trip.getCapacity());
        assertEquals(departure, trip.getDepartureAt());
        assertEquals(1, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", scheduledEventId));
    }

    @Test
    void privateTripScheduled_isIgnoredButMarkedProcessed() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        send(tripTopic, tripId, envelope(eventId, EventTypes.TRIP_SCHEDULED, tripId, Instant.now(),
                tripPayload(tripId, "PRIVATE", now().plusDays(1), 0)));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(1, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId)));
        assertFalse(tripSnapshotRepository.existsById(tripId));
    }

    @Test
    void tripStarted_expiresPendingOrdersAndKeepsConfirmed() throws Exception {
        TripSnapshotEntity trip = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusMinutes(10), TripStatus.SCHEDULED);
        PurchaseOrderEntity pending = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING,
                now().plusMinutes(5), 1);
        PurchaseOrderEntity confirmed = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CONFIRMED,
                TicketStatus.CONFIRMED, now().plusMinutes(5), 2);

        send(tripTopic, trip.getTripId(), envelope(UUID.randomUUID(), EventTypes.TRIP_STARTED, trip.getTripId(),
                Instant.now(), Map.of("tripId", trip.getTripId().toString(), "type", "REGULAR")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(OrderStatus.EXPIRED, load(pending.getId()).getStatus()));
        PurchaseOrderEntity expired = load(pending.getId());
        assertEquals(CancelReason.TRIP_STARTED, expired.getFailureReason());
        assertEquals(TicketStatus.CANCELLED, expired.getTickets().get(0).getStatus());
        assertEquals(TripStatus.IN_TRANSIT, tripSnapshotRepository.findById(trip.getTripId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.CONFIRMED, load(confirmed.getId()).getStatus());
    }

    @Test
    void tripDeleted_cancelsOrdersAndRecordsCancellationsAndWalletRefunds() throws Exception {
        TripSnapshotEntity trip = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.SCHEDULED);
        PurchaseOrderEntity online = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CONFIRMED, TicketStatus.CONFIRMED,
                now().plusMinutes(5), 1);
        PurchaseOrderEntity counter = saveCounterOrder(trip, CUSTOMER_ID, "CF", 2);
        PurchaseOrderEntity pending = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING,
                now().plusMinutes(5), 3);

        send(tripTopic, trip.getTripId(), envelope(UUID.randomUUID(), EventTypes.TRIP_DELETED, trip.getTripId(),
                Instant.now(), Map.of("tripId", trip.getTripId().toString())));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(OrderStatus.CANCELLED, load(online.getId()).getStatus()));
        UUID onlineTicket = load(online.getId()).getTickets().get(0).getId();
        UUID counterTicket = load(counter.getId()).getTickets().get(0).getId();
        UUID pendingTicket = load(pending.getId()).getTickets().get(0).getId();
        assertEquals(OrderStatus.CANCELLED, load(counter.getId()).getStatus());
        assertEquals(OrderStatus.CANCELLED, load(pending.getId()).getStatus());
        assertEquals(List.of("RefundRequested", "TicketCancelled"),
                outboxEventTypes(onlineTicket).stream().sorted().toList());
        assertEquals(List.of("TicketCancelled"), outboxEventTypes(counterTicket));
        assertEquals(List.of(), outboxEventTypes(pendingTicket));
        JsonNode cancelled = jsonMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ?", String.class, counterTicket));
        assertEquals("CASH", cancelled.get("payload").get("refundMethod").asString());
        assertEquals(CancelReason.TRIP_DELETED, cancelled.get("payload").get("reason").asString());
    }

    @Test
    void paymentCompleted_confirmsOrderAndRecordsTicketPurchased() throws Exception {
        TripSnapshotEntity trip = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.SCHEDULED);
        PurchaseOrderEntity order = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING,
                now().plusMinutes(5), 1, 2);

        send(paymentTopic, CUSTOMER_ID, envelope(UUID.randomUUID(), EventTypes.PAYMENT_COMPLETED, CUSTOMER_ID,
                Instant.now(), paymentPayload("RENTAL", order.getId(), null)));
        send(paymentTopic, CUSTOMER_ID, envelope(UUID.randomUUID(), EventTypes.PAYMENT_COMPLETED, CUSTOMER_ID,
                Instant.now(), paymentPayload("ORDER", order.getId(), null)));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(OrderStatus.CONFIRMED, load(order.getId()).getStatus()));
        PurchaseOrderEntity confirmed = load(order.getId());
        assertNotNull(confirmed.getConfirmedAt());
        assertTrue(confirmed.getTickets().stream().allMatch(ticket -> ticket.getStatus() == TicketStatus.CONFIRMED));
        assertEquals(List.of("TicketPurchased"), outboxEventTypes(order.getId()));
    }

    @Test
    void paymentFailed_failsOrderAndFreesSeats() throws Exception {
        TripSnapshotEntity trip = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.SCHEDULED);
        PurchaseOrderEntity order = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.PENDING, TicketStatus.PENDING,
                now().plusMinutes(5), 1);

        send(paymentTopic, CUSTOMER_ID, envelope(UUID.randomUUID(), EventTypes.PAYMENT_FAILED, CUSTOMER_ID,
                Instant.now(), paymentPayload("ORDER", order.getId(), "INSUFFICIENT_FUNDS")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(OrderStatus.FAILED, load(order.getId()).getStatus()));
        PurchaseOrderEntity failed = load(order.getId());
        assertEquals("INSUFFICIENT_FUNDS", failed.getFailureReason());
        TicketEntity ticket = failed.getTickets().get(0);
        assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
        assertEquals(CancelReason.PAYMENT_FAILED, ticket.getCancelReason());
        assertEquals(List.of(), outboxEventTypes(order.getId()));
    }

    @Test
    void refundCompleted_marksCancelledTicketRefundedOnlyOnce() throws Exception {
        TripSnapshotEntity trip = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.DELETED);
        PurchaseOrderEntity order = saveOnlineOrder(trip, CUSTOMER_ID, OrderStatus.CANCELLED, TicketStatus.CANCELLED,
                now().plusMinutes(5), 1);
        UUID ticketId = load(order.getId()).getTickets().get(0).getId();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("ticketId", ticketId.toString(), "amount", 50.0);

        send(paymentTopic, CUSTOMER_ID, envelope(eventId, EventTypes.REFUND_COMPLETED, CUSTOMER_ID, Instant.now(),
                payload));
        send(paymentTopic, CUSTOMER_ID, envelope(eventId, EventTypes.REFUND_COMPLETED, CUSTOMER_ID, Instant.now(),
                payload));
        send(paymentTopic, CUSTOMER_ID, envelope(UUID.randomUUID(), EventTypes.REFUND_COMPLETED, CUSTOMER_ID,
                Instant.now(), Map.of("ticketId", UUID.randomUUID().toString(), "amount", 10)));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(TicketStatus.REFUNDED, ticketRepository.findById(ticketId).orElseThrow().getStatus()));
        assertEquals(1, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId));
    }

    @Test
    void outboxEvent_isPublishedToTicketEventsWithOrderAsKey() throws Exception {
        TripSnapshotEntity trip = saveTrip(ORIGIN_ID, DESTINATION_ID, now().plusDays(1), TripStatus.SCHEDULED);
        MvcResult result = mockMvc.perform(post("/orders").with(customer(CUSTOMER_ID, "12345678"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":\"%s\",\"seats\":[4,5]}".formatted(trip.getTripId())))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID orderId = idAt(result, "$.orderId");

        ConsumerRecord<String, String> record = awaitRecord(ticketTopic, orderId.toString());

        JsonNode envelope = jsonMapper.readTree(record.value());
        assertEquals(EventTypes.PAYMENT_REQUESTED, envelope.get("eventType").asString());
        assertEquals(orderId.toString(), envelope.get("aggregateId").asString());
        assertEquals(1, envelope.get("version").asInt());
        JsonNode payload = envelope.get("payload");
        assertEquals("ORDER", payload.get("referenceType").asString());
        assertEquals(orderId.toString(), payload.get("referenceId").asString());
        assertEquals(CUSTOMER_ID.toString(), payload.get("customerId").asString());
        assertEquals("12345678", payload.get("customerNit").asString());
        assertEquals(0, new BigDecimal("100.00").compareTo(payload.get("amount").decimalValue()));
        assertEquals(today().toString(), payload.get("paymentDate").asString());
        await().atMost(TIMEOUT).untilAsserted(() -> assertNotNull(jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE aggregate_id = ?", LocalDateTime.class, orderId)));
    }

    @Test
    void malformedEvents_endInDeadLetterTopics() throws Exception {
        UUID tripKey = UUID.randomUUID();
        UUID paymentKey = UUID.randomUUID();

        send(tripTopic, tripKey, "{no es json");
        send(paymentTopic, paymentKey, "{no es json");

        assertEquals("{no es json", awaitRecord(tripTopic + ".DLT", tripKey.toString()).value());
        assertEquals("{no es json", awaitRecord(paymentTopic + ".DLT", paymentKey.toString()).value());
    }

    private PurchaseOrderEntity load(UUID orderId) {
        return transactionTemplate.execute(status -> {
            PurchaseOrderEntity order = purchaseOrderRepository.findById(orderId).orElseThrow();
            order.getTickets().size();
            return order;
        });
    }

    private void send(String topic, UUID key, String value) throws Exception {
        kafkaTemplate.send(topic, key.toString(), value).get(10, TimeUnit.SECONDS);
    }

    private String envelope(UUID eventId, String eventType, UUID aggregateId, Instant occurredAt,
                            Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "eventType", eventType,
                "occurredAt", occurredAt.toString(),
                "aggregateId", aggregateId.toString(),
                "version", 1,
                "correlationId", UUID.randomUUID().toString(),
                "payload", payload));
    }

    private static Map<String, Object> tripPayload(UUID tripId, String type, LocalDateTime departure, int price) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("type", type);
        payload.put("routeId", UUID.randomUUID().toString());
        payload.put("originBranchId", ORIGIN_ID.toString());
        payload.put("destinationBranchId", DESTINATION_ID.toString());
        payload.put("originName", "Central");
        payload.put("destinationName", "Xela");
        payload.put("busId", UUID.randomUUID().toString());
        payload.put("busPlate", "P-123ABC");
        payload.put("capacity", 40);
        payload.put("seatLayout", Map.of("rows", 10, "columns", 4, "aisleAfterColumn", 2));
        payload.put("ticketPrice", price);
        payload.put("distanceKm", 200.5);
        payload.put("departureAt", departure.toString());
        payload.put("estimatedArrival", departure.plusHours(4).toString());
        payload.put("status", "SCHEDULED");
        return payload;
    }

    private static Map<String, Object> paymentPayload(String referenceType, UUID referenceId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("referenceType", referenceType);
        payload.put("referenceId", referenceId.toString());
        payload.put("reason", reason);
        return payload;
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), "it")) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(60)).pollInSameThread().untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                assertTrue(received.stream().anyMatch(record -> key.equals(record.key())),
                        "no llegó el mensaje con key " + key + " a " + topic);
            });
        }
        return received.stream().filter(record -> key.equals(record.key())).findFirst().orElseThrow();
    }
}
