package cl.duoc.pedidos360.catalog.messaging.kitchen;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cl.duoc.pedidos360.catalog.dto.KitchenTicketCommand;
import cl.duoc.pedidos360.catalog.messaging.support.JsonCommandReader;
import cl.duoc.pedidos360.catalog.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.catalog.service.KitchenTicketService;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

/** Decisiones ACK/NACK del listener de cocina con un Channel simulado (sin broker ni base). */
class KitchenTicketListenerTest {
    static final long TAG = 9L;
    static final String EXAMPLE = """
            {"schemaVersion":1,"eventId":"0b2f6a55-3f0f-4f61-b3a8-7e7d64d0f0a1","orderId":42,"customerId":"cliente-demo",\
            "items":[{"productId":7,"quantity":2},{"productId":9,"quantity":1}],"occurredAt":"2026-10-08T15:30:00Z",\
            "traceId":"trace-demo-001"}""";
    Channel channel = mock(Channel.class);
    KitchenTicketService service = mock(KitchenTicketService.class);
    KitchenTicketListener listener = new KitchenTicketListener(
            new JsonCommandReader(JsonMapper.builder().findAndAddModules().build(),
                    Validation.buildDefaultValidatorFactory().getValidator(), 32768),
            new ManualAckHandler(3, Duration.ofMillis(5), 2, Duration.ofMillis(20)), service);

    Message message(String payload, String messageId) {
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8)).setContentType("application/json")
                .setMessageId(messageId).setType("kitchen.ticket").setCorrelationId("order-42")
                .setHeader("x-trace-id", "trace-demo-001").build();
    }

    void assertDeadLetteredWithoutRetries() throws Exception {
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(service);
    }

    @AfterEach
    void neverRequeues() throws Exception {
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void parsesTheContractExampleStrictlyAndAcknowledges() throws Exception {
        when(service.register(any())).thenReturn(KitchenTicketService.Result.CREATED);
        listener.onMessage(message(EXAMPLE, "0b2f6a55-3f0f-4f61-b3a8-7e7d64d0f0a1"), channel, TAG);
        var command = ArgumentCaptor.forClass(KitchenTicketCommand.class);
        verify(service).register(command.capture());
        assertEquals(new KitchenTicketCommand(1, UUID.fromString("0b2f6a55-3f0f-4f61-b3a8-7e7d64d0f0a1"), 42L,
                "cliente-demo", List.of(new KitchenTicketCommand.Item(7L, 2), new KitchenTicketCommand.Item(9L, 1)),
                Instant.parse("2026-10-08T15:30:00Z"), "trace-demo-001"), command.getValue());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void acknowledgesDuplicatesWithoutRepeatingEffects() throws Exception {
        when(service.register(any())).thenReturn(KitchenTicketService.Result.DUPLICATE);
        listener.onMessage(message(EXAMPLE, null), channel, TAG);
        verify(service, times(1)).register(any());
        verify(channel).basicAck(TAG, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-field", "empty-items", "zero-quantity", "fractional-quantity", "schema-2",
            "bad-trace", "missing-event", "not-json", "duplicate-key", "too-many-items"})
    void rejectsInvalidCommandsImmediatelyToTheDeadLetterQueue(String scenario) throws Exception {
        String payload = switch (scenario) {
            case "unknown-field" -> EXAMPLE.replace("\"orderId\":42", "\"orderId\":42,\"price\":1");
            case "empty-items" -> EXAMPLE.replaceAll("\\[.*]", "[]");
            case "zero-quantity" -> EXAMPLE.replace("\"quantity\":2", "\"quantity\":0");
            case "fractional-quantity" -> EXAMPLE.replace("\"quantity\":2", "\"quantity\":2.5");
            case "schema-2" -> EXAMPLE.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            case "bad-trace" -> EXAMPLE.replace("trace-demo-001", "trace demo");
            case "missing-event" -> EXAMPLE.replace("\"eventId\":\"0b2f6a55-3f0f-4f61-b3a8-7e7d64d0f0a1\",", "");
            case "duplicate-key" -> EXAMPLE.replace("\"orderId\":42", "\"orderId\":42,\"orderId\":43");
            case "too-many-items" -> EXAMPLE.replaceAll("\\[.*]", "[" + String.join(",",
                    java.util.Collections.nCopies(51, "{\"productId\":7,\"quantity\":1}")) + "]");
            default -> "not-json";
        };
        listener.onMessage(message(payload, null), channel, TAG);
        assertDeadLetteredWithoutRetries();
    }

    @Test
    void rejectsMessageIdThatDoesNotMatchTheEventId() throws Exception {
        listener.onMessage(message(EXAMPLE, UUID.randomUUID().toString()), channel, TAG);
        assertDeadLetteredWithoutRetries();
    }

    @Test
    void rejectsOtherContentTypesAndOversizedBodies() throws Exception {
        listener.onMessage(new Message(EXAMPLE.getBytes(StandardCharsets.UTF_8)), channel, TAG);
        listener.onMessage(message(" ".repeat(32769), null), channel, TAG);
        verify(channel, times(2)).basicNack(TAG, false, false);
        verifyNoInteractions(service);
    }

    @Test
    void retriesDatabaseFailuresAndThenDeadLetters() throws Exception {
        when(service.register(any())).thenThrow(new DataAccessResourceFailureException("base caída"));
        listener.onMessage(message(EXAMPLE, null), channel, TAG);
        verify(service, times(3)).register(any());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
