package cl.duoc.pedidos360.report.messaging.invoice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.report.dto.InvoiceCommand;
import cl.duoc.pedidos360.report.exception.InvoiceRejectedException;
import cl.duoc.pedidos360.report.messaging.support.JsonCommandReader;
import cl.duoc.pedidos360.report.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.report.service.InvoiceService;
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

/** Decisiones ACK/NACK del listener de boletas con un Channel simulado (sin broker ni base). */
public class InvoiceCommandListenerTest {
    static final long TAG = 11L;
    public static final String EXAMPLE = """
            {"schemaVersion":1,"eventId":"9d0c8e1a-1b2c-4d3e-8f4a-5b6c7d8e9f00","orderId":42,"customerId":"cliente-demo",\
            "items":[{"productId":7,"quantity":2,"unitPrice":1500.00},{"productId":9,"quantity":1,"unitPrice":2990.00}],\
            "total":5990.00,"occurredAt":"2026-10-08T18:00:00Z","traceId":"trace-demo-001"}""";
    Channel channel = mock(Channel.class);
    InvoiceService service = mock(InvoiceService.class);
    InvoiceCommandListener listener = new InvoiceCommandListener(
            new JsonCommandReader(JsonMapper.builder().findAndAddModules().build(),
                    Validation.buildDefaultValidatorFactory().getValidator(), 32768),
            new ManualAckHandler(3, Duration.ofMillis(5), 2, Duration.ofMillis(20)), service);

    static Message message(String payload, String messageId) {
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8)).setContentType("application/json")
                .setMessageId(messageId).setType("invoice.gen").setCorrelationId("order-42")
                .setHeader("x-trace-id", "trace-demo-001").build();
    }

    @AfterEach
    void neverRequeues() throws Exception {
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void parsesTheContractExampleStrictlyAndAcknowledges() throws Exception {
        when(service.issue(any())).thenReturn(InvoiceService.Result.ISSUED);
        listener.onMessage(message(EXAMPLE, "9d0c8e1a-1b2c-4d3e-8f4a-5b6c7d8e9f00"), channel, TAG);
        var command = ArgumentCaptor.forClass(InvoiceCommand.class);
        verify(service).issue(command.capture());
        assertEquals(UUID.fromString("9d0c8e1a-1b2c-4d3e-8f4a-5b6c7d8e9f00"), command.getValue().eventId());
        assertEquals(new BigDecimal("5990.00"), command.getValue().total());
        assertEquals(new BigDecimal("1500.00"), command.getValue().items().getFirst().unitPrice());
        assertEquals(2, command.getValue().items().size());
        assertEquals(Instant.parse("2026-10-08T18:00:00Z"), command.getValue().occurredAt());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void acknowledgesInvoicesAlreadyIssued() throws Exception {
        when(service.issue(any())).thenReturn(InvoiceService.Result.DUPLICATE);
        listener.onMessage(message(EXAMPLE, null), channel, TAG);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void sendsItemsThatDoNotMatchTheTotalToTheDeadLetterQueueWithoutRetries() throws Exception {
        when(service.issue(any())).thenThrow(new InvoiceRejectedException("no cuadra"));
        listener.onMessage(message(EXAMPLE, null), channel, TAG);
        verify(service, times(1)).issue(any());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void retriesDatabaseFailuresAndThenDeadLetters() throws Exception {
        when(service.issue(any())).thenThrow(new DataAccessResourceFailureException("base caída"));
        listener.onMessage(message(EXAMPLE, null), channel, TAG);
        verify(service, times(3)).issue(any());
        verify(channel).basicNack(TAG, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"three-decimals", "zero-total", "negative-price", "unknown-field", "string-total",
            "empty-items", "bad-trace", "message-id"})
    void rejectsInvalidCommandsImmediately(String scenario) throws Exception {
        String messageId = null;
        String payload = switch (scenario) {
            case "three-decimals" -> EXAMPLE.replace("\"unitPrice\":1500.00", "\"unitPrice\":1500.001");
            case "zero-total" -> EXAMPLE.replace("\"total\":5990.00", "\"total\":0");
            case "negative-price" -> EXAMPLE.replace("\"unitPrice\":2990.00", "\"unitPrice\":-2990.00");
            case "unknown-field" -> EXAMPLE.replace("\"total\":5990.00", "\"total\":5990.00,\"tax\":1");
            case "string-total" -> EXAMPLE.replace("\"total\":5990.00", "\"total\":\"5990.00\"");
            case "empty-items" -> EXAMPLE.replaceAll("\\[.*]", "[]");
            case "bad-trace" -> EXAMPLE.replace("trace-demo-001", "trace/demo");
            default -> {
                messageId = UUID.randomUUID().toString();
                yield EXAMPLE;
            }
        };
        listener.onMessage(message(payload, messageId), channel, TAG);
        verify(channel).basicNack(TAG, false, false);
        verifyNoInteractions(service);
    }
}
