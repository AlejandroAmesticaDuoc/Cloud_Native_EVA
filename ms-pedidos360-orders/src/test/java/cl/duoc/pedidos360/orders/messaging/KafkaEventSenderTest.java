package cl.duoc.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import cl.duoc.pedidos360.orders.dto.OrderStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventSenderTest {
    JsonMapper json = JsonMapper.builder().build();
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, OrderEvent> template = mock(KafkaTemplate.class);
    KafkaEventSender sender = new KafkaEventSender(template, json, "orders.events");

    static OrderEvent event(long orderId) {
        Instant created = Instant.parse("2026-11-10T12:00:00Z");
        return new OrderEvent(1, UUID.fromString("656efca0-3b3a-423f-a8a0-7a1c21e89d6e"),
                OrderEvent.EventType.OrderAccepted, orderId, 2, created.plusSeconds(60), "trace-21", "operador",
                "alice", OrderStatus.CREADO, OrderStatus.ACEPTADO, created, new BigDecimal("2501.00"));
    }

    @Test
    void sendsTheTypedEventUsingTheOrderIdAsKey() throws Exception {
        when(template.send(eq("orders.events"), eq("21"), any(OrderEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        sender.send(21L, json.writeValueAsString(event(21)));
        var sent = ArgumentCaptor.forClass(OrderEvent.class);
        verify(template).send(eq("orders.events"), eq("21"), sent.capture());
        assertEquals(event(21), sent.getValue());
    }

    @Test
    void serializesTheContractV1AsPlainJsonWithoutJavaTypeHeaders() {
        try (var serializer = new JacksonJsonSerializer<OrderEvent>().noTypeInfo()) {
            var headers = new org.apache.kafka.common.header.internals.RecordHeaders();
            var tree = json.readTree(serializer.serialize("orders.events", headers, event(21)));
            assertEquals(13, tree.size());
            assertEquals("OrderAccepted", tree.get("eventType").asString());
            assertEquals("2026-11-10T12:01:00Z", tree.get("occurredAt").asString());
            assertEquals(0, headers.toArray().length);
        }
    }

    @Test
    void rejectsAnOutboxPayloadThatBelongsToAnotherOrder() {
        String payload = json.writeValueAsString(event(22));
        assertThrows(IllegalStateException.class, () -> sender.send(21L, payload));
        verifyNoInteractions(template);
    }

    @Test
    void waitsForBrokerAcknowledgement() throws Exception {
        var acknowledged = new CompletableFuture<SendResult<String, OrderEvent>>();
        when(template.send(eq("orders.events"), eq("21"), any(OrderEvent.class))).thenReturn(acknowledged);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> { sender.send(event(21)); return true; });
            verify(template, timeout(1000)).send(eq("orders.events"), eq("21"), any(OrderEvent.class));
            assertFalse(result.isDone());
            acknowledged.complete(null);
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void propagatesAsynchronousDeliveryFailure() {
        when(template.send(eq("orders.events"), eq("21"), any(OrderEvent.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker offline")));
        assertThrows(ExecutionException.class, () -> sender.send(event(21)));
    }

    @Test
    void propagatesMetadataFailure() {
        when(template.send(eq("orders.events"), eq("21"), any(OrderEvent.class)))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("missing metadata"));
        assertThrows(org.apache.kafka.common.errors.TimeoutException.class, () -> sender.send(event(21)));
    }
}
