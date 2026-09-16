package cl.duoc.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaEventSenderTest {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    KafkaEventSender sender = new KafkaEventSender(template, "orders.events");

    @Test
    void sendsJsonUsingTheOrderIdAsKey() throws Exception {
        when(template.send("orders.events", "21", "{\"eventId\":\"event-1\"}"))
                .thenReturn(CompletableFuture.completedFuture(null));
        sender.send(21L, "{\"eventId\":\"event-1\"}");
        verify(template).send("orders.events", "21", "{\"eventId\":\"event-1\"}");
    }

    @Test
    void waitsForBrokerAcknowledgement() throws Exception {
        var acknowledged = new CompletableFuture<SendResult<String, String>>();
        when(template.send("orders.events", "21", "{}")).thenReturn(acknowledged);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> { sender.send(21L, "{}"); return true; });
            verify(template, timeout(1000)).send("orders.events", "21", "{}");
            assertFalse(result.isDone());
            acknowledged.complete(null);
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void propagatesAsynchronousDeliveryFailure() {
        when(template.send("orders.events", "21", "{}"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker offline")));
        assertThrows(ExecutionException.class, () -> sender.send(21L, "{}"));
    }

    @Test
    void propagatesMetadataFailure() {
        when(template.send("orders.events", "21", "{}"))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("missing metadata"));
        assertThrows(org.apache.kafka.common.errors.TimeoutException.class, () -> sender.send(21L, "{}"));
    }
}
