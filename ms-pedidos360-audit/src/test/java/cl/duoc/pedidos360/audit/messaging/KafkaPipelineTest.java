package cl.duoc.pedidos360.audit.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import cl.duoc.pedidos360.audit.dto.AuditTimelineEntry;
import cl.duoc.pedidos360.audit.dto.OrderEvent;
import cl.duoc.pedidos360.audit.exception.InvalidEventException;
import cl.duoc.pedidos360.audit.exception.TimelinePublishException;
import cl.duoc.pedidos360.audit.service.AuditStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import tools.jackson.databind.json.JsonMapper;

class KafkaPipelineTest {
    final OrderEventDeserializer deserializer = new OrderEventDeserializer();

    byte[] created(long order) {
        return EventParserTest.event(order, 1, "OrderCreated", null, "CREADO").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void deserializesTheContractIntoTheTypedDto() {
        OrderEvent event = deserializer.deserialize("orders.events", created(10));
        assertEquals(10L, event.orderId());
        assertEquals(OrderEvent.EventType.OrderCreated, event.eventType());
        assertEquals("100.50", event.total().toPlainString());
    }

    @Test
    void errorHandlingDeserializerTurnsAPoisonPayloadIntoADeserializationException() {
        try (var safe = new ErrorHandlingDeserializer<>(new OrderEventDeserializer())) {
            var headers = new org.apache.kafka.common.header.internals.RecordHeaders();
            assertNull(safe.deserialize("orders.events", headers, "{\"token\":\"secret\"}".getBytes(StandardCharsets.UTF_8)));
            assertNotNull(headers.lastHeader("springDeserializerExceptionValue"));
        }
    }

    @Test
    void classifiesFailures() {
        var deserialization = new DeserializationException("bad", "{}".getBytes(StandardCharsets.UTF_8), false, new IllegalArgumentException());
        assertEquals("INVALID_EVENT", KafkaFailures.reason(deserialization));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), KafkaFailures.rawPayload(deserialization));
        var conflict = new ListenerExecutionFailedException("listener", new InvalidEventException("EVENT_CONFLICT", "x"));
        assertEquals("EVENT_CONFLICT", KafkaFailures.reason(conflict));
        assertFalse(KafkaFailures.isTransient(conflict));
        assertTrue(KafkaFailures.isTransient(new ListenerExecutionFailedException("l", new DataAccessResourceFailureException("db"))));
        assertTrue(KafkaFailures.isTransient(new TimelinePublishException("kafka", null)));
        assertEquals("PROCESSING_FAILED", KafkaFailures.reason(new IllegalStateException("boom")));
        assertFalse(KafkaFailures.isTransient(new IllegalStateException("boom")));
    }

    @Test
    void recovererRecordsTheRejectionAndPublishesToTheDeadLetterTopic() {
        var store = mock(AuditStore.class);
        var deadLetters = mock(DeadLetterPublishingRecoverer.class);
        var recoverer = new RejectedEventRecoverer(store, JsonMapper.builder().build(), deadLetters, "orders.events.audit.DLT");
        var record = new ConsumerRecord<String, Object>("orders.events", 2, 41L, "10", null);
        var failure = new ListenerExecutionFailedException("l",
                new DeserializationException("bad", "{\"x\":1}".getBytes(StandardCharsets.UTF_8), false, new IllegalArgumentException()));
        recoverer.accept(record, null, failure);
        verify(store).reject("orders.events", 2, 41L, "INVALID_EVENT", "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        verify(deadLetters).accept(record, null, failure);
    }

    @Test
    void timelinePublisherUsesTheOrderAsKeyAndWrapsBrokerFailures() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        var publisher = new KafkaTimelinePublisher(kafka, "audit.timeline");
        var entry = AuditTimelineEntry.from(deserializer.deserialize("orders.events", created(10)), java.time.Instant.now());
        when(kafka.send(eq("audit.timeline"), eq("10"), any())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish(entry);
        verify(kafka).send("audit.timeline", "10", entry);
        when(kafka.send(eq("audit.timeline"), eq("10"), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertThrows(TimelinePublishException.class, () -> publisher.publish(entry));
        var event = deserializer.deserialize("orders.events", created(11));
        assertEquals(AuditTimelineEntry.from(event, java.time.Instant.EPOCH).entryId(),
                AuditTimelineEntry.from(event, java.time.Instant.now()).entryId(), "entryId determinista por evento");
    }
}
