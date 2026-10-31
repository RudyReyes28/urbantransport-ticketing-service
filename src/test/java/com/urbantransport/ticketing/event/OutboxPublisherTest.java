package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.config.TicketingKafkaProperties;
import com.urbantransport.ticketing.domain.OutboxEventEntity;
import com.urbantransport.ticketing.repository.OutboxEventRepository;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final String TOPIC = "ticket-events";

    private final OutboxEventEntity first = event(1, EventTypes.AGGREGATE_ORDER);
    private final OutboxEventEntity second = event(2, EventTypes.AGGREGATE_TICKET);
    private final OutboxEventEntity third = event(3, EventTypes.AGGREGATE_ORDER);

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private CompletableFuture<SendResult<String, String>> pendingSend;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxPublisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate,
                new TicketingKafkaProperties(TOPIC, "trip-events", "payment-events"));
    }

    @Test
    void pendingEvents_arePublishedInOrderWithAggregateAsKey() {
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPending();

        InOrder inOrder = inOrder(kafkaTemplate, outboxEventRepository);
        inOrder.verify(kafkaTemplate).send(TOPIC, first.getAggregateId().toString(), first.getPayload());
        inOrder.verify(outboxEventRepository).markPublished(eq(first.getId()), any(LocalDateTime.class));
        inOrder.verify(kafkaTemplate).send(TOPIC, second.getAggregateId().toString(), second.getPayload());
        inOrder.verify(outboxEventRepository).markPublished(eq(second.getId()), any(LocalDateTime.class));
    }

    @Test
    void noPendingEvents_sendsNothing() {
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        outboxPublisher.publishPending();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void failedSend_stopsWithoutMarkingToKeepOrder() {
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(first, second, third));
        when(kafkaTemplate.send(TOPIC, first.getAggregateId().toString(), first.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(TOPIC, second.getAggregateId().toString(), second.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker caído")));

        outboxPublisher.publishPending();

        verify(outboxEventRepository).markPublished(eq(first.getId()), any(LocalDateTime.class));
        verify(outboxEventRepository, never()).markPublished(eq(second.getId()), any(LocalDateTime.class));
        verify(kafkaTemplate, never()).send(TOPIC, third.getAggregateId().toString(), third.getPayload());
    }

    @Test
    void sendTimeout_stopsWithoutMarking() throws Exception {
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(pendingSend);
        when(pendingSend.get(5, TimeUnit.SECONDS)).thenThrow(new TimeoutException());

        outboxPublisher.publishPending();

        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(kafkaTemplate, never()).send(TOPIC, second.getAggregateId().toString(), second.getPayload());
    }

    @Test
    void interruptedSend_stopsWithoutMarkingAndKeepsInterruptFlag() throws Exception {
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(pendingSend);
        when(pendingSend.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        outboxPublisher.publishPending();

        assertTrue(Thread.interrupted());
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(kafkaTemplate, never()).send(TOPIC, second.getAggregateId().toString(), second.getPayload());
    }

    @Test
    void unknownAggregateType_stopsWithoutSending() {
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(event(4, "Rental"), first));

        outboxPublisher.publishPending();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    private static OutboxEventEntity event(int number, String aggregateType) {
        return OutboxEventEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-00000000000" + number))
                .aggregateType(aggregateType)
                .aggregateId(UUID.fromString("00000000-0000-0000-0000-00000000010" + number))
                .eventType(EventTypes.PAYMENT_REQUESTED)
                .payload("{\"eventType\":\"PaymentRequested\",\"n\":" + number + "}")
                .build();
    }
}
