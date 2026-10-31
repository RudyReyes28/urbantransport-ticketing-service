package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.service.OrderPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentEventsListenerTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TICKET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private OrderPaymentService orderPaymentService;

    @Captor
    private ArgumentCaptor<RefundCompletedEvent.RefundCompletedPayload> refundCaptor;

    private PaymentEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentEventsListener(jsonMapper, orderPaymentService);
    }

    @Test
    void paymentCompleted_isHandledWithReference() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("referenceType", "ORDER");
        payload.put("referenceId", ORDER_ID.toString());
        payload.put("reason", null);

        listener.onEvent(message(EventTypes.PAYMENT_COMPLETED, payload));

        verify(orderPaymentService).handlePaymentCompleted(EVENT_ID,
                new PaymentResultEvent.PaymentResultPayload("ORDER", ORDER_ID, null));
    }

    @Test
    void paymentFailed_isHandledWithReason() {
        listener.onEvent(message(EventTypes.PAYMENT_FAILED, Map.of("referenceType", "ORDER",
                "referenceId", ORDER_ID.toString(), "reason", "INSUFFICIENT_FUNDS", "extra", true)));

        verify(orderPaymentService).handlePaymentFailed(EVENT_ID,
                new PaymentResultEvent.PaymentResultPayload("ORDER", ORDER_ID, "INSUFFICIENT_FUNDS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"50", "50.25"})
    void refundCompleted_parsesTicketAndAmount(String amount) {
        listener.onEvent(message(EventTypes.REFUND_COMPLETED, Map.of("ticketId", TICKET_ID.toString(),
                "amount", new BigDecimal(amount))));

        verify(orderPaymentService).handleRefundCompleted(eq(EVENT_ID), refundCaptor.capture());
        assertEquals(TICKET_ID, refundCaptor.getValue().ticketId());
        assertEquals(0, new BigDecimal(amount).compareTo(refundCaptor.getValue().amount()));
    }

    @Test
    void refundCompletedWithoutPayload_passesNull() {
        Map<String, Object> envelope = new HashMap<>(envelope(EventTypes.REFUND_COMPLETED));
        envelope.put("payload", null);

        listener.onEvent(jsonMapper.writeValueAsString(envelope));

        verify(orderPaymentService).handleRefundCompleted(eq(EVENT_ID), refundCaptor.capture());
        assertNull(refundCaptor.getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WalletRecharged", "PaymentRequested", ""})
    void otherEvent_isIgnored(String eventType) {
        listener.onEvent(message(eventType, Map.of("referenceType", "ORDER")));

        verifyNoInteractions(orderPaymentService);
    }

    @Test
    void eventWithoutType_isIgnored() {
        listener.onEvent("{\"eventId\":\"%s\",\"payload\":{}}".formatted(EVENT_ID));

        verifyNoInteractions(orderPaymentService);
    }

    @Test
    void malformedMessage_throwsSoItGoesToRetryAndDlt() {
        assertThrows(JacksonException.class, () -> listener.onEvent("{no es json"));

        verifyNoInteractions(orderPaymentService);
    }

    private Map<String, Object> envelope(String eventType) {
        return Map.of(
                "eventId", EVENT_ID.toString(),
                "eventType", eventType,
                "occurredAt", "2026-10-29T12:00:00Z",
                "aggregateId", ORDER_ID.toString(),
                "version", 1);
    }

    private String message(String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new HashMap<>(envelope(eventType));
        envelope.put("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }
}
