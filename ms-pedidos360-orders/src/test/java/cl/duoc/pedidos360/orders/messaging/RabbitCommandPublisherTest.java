package cl.duoc.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import cl.duoc.pedidos360.orders.config.MessagingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

class RabbitCommandPublisherTest {
    static final String EVENT_ID = "6f1c3f0e-8a4b-4c1e-9a43-2a7d1e5b9c10";
    static final String PAYLOAD = """
            {"schemaVersion":1,"eventId":"6f1c3f0e-8a4b-4c1e-9a43-2a7d1e5b9c10","orderId":42,\
            "customerId":"cliente-demo","status":"ACEPTADO","occurredAt":"2026-10-08T15:30:00Z","traceId":"trace-demo-001"}""";
    static final MessagingProperties MESSAGING = new MessagingProperties(
            new MessagingProperties.Exchanges("cmd.direct", "cmd.topic", "cmd.dead.dlx"),
            new MessagingProperties.Routes(
                    new MessagingProperties.EmailRoute("q.cmd.email", "q.cmd.email.dlq", "email.send", "email.#", "email.send.high"),
                    new MessagingProperties.Route("q.cmd.kitchen", "q.cmd.kitchen.dlq", "kitchen.ticket", "kitchen.#"),
                    new MessagingProperties.Route("q.cmd.invoice", "q.cmd.invoice.dlq", "invoice.gen", "invoice.#")),
            new MessagingProperties.Queues(10000, Duration.ofDays(7), 10000));

    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    RabbitCommandPublisher publisher = new RabbitCommandPublisher(rabbit, MESSAGING,
            JsonMapper.builder().build(), "ms-pedidos360-orders");

    void confirm(boolean ack, boolean returned) {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE",
                        call.getArgument(0), call.getArgument(1)));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "reject-publish"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @ParameterizedTest
    @CsvSource({"EMAIL,cmd.direct,email.send", "EMAIL_PRIORITY,cmd.topic,email.send.high",
        "KITCHEN_TICKET,cmd.direct,kitchen.ticket", "INVOICE,cmd.direct,invoice.gen"})
    void routesEachCommandTypeToItsExchangeAndRoutingKey(CommandType type, String exchange, String routingKey)
            throws Exception {
        confirm(true, false);
        publisher.publish(type, EVENT_ID, PAYLOAD);
        var message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(exchange), eq(routingKey), message.capture(), any(CorrelationData.class));
        assertEquals(routingKey, message.getValue().getMessageProperties().getType());
    }

    @Test
    void setsTheCommonEnvelopeAndWaitsForConfirmation() throws Exception {
        confirm(true, false);
        publisher.publish(CommandType.EMAIL, EVENT_ID, PAYLOAD);
        var message = ArgumentCaptor.forClass(Message.class);
        var correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).send(anyString(), anyString(), message.capture(), correlation.capture());
        var properties = message.getValue().getMessageProperties();
        assertEquals(EVENT_ID, properties.getMessageId());
        assertEquals(EVENT_ID, correlation.getValue().getId());
        assertEquals("email.send", properties.getType());
        assertEquals("order-42", properties.getCorrelationId());
        assertEquals(Instant.parse("2026-10-08T15:30:00Z"), properties.getTimestamp().toInstant());
        assertEquals("ms-pedidos360-orders", properties.getAppId());
        assertEquals("application/json", properties.getContentType());
        assertEquals("UTF-8", properties.getContentEncoding());
        assertEquals(MessageDeliveryMode.PERSISTENT, properties.getDeliveryMode());
        assertEquals("trace-demo-001", properties.getHeader("x-trace-id"));
        assertEquals(Integer.valueOf(1), properties.getHeader("x-schema-version"));
        assertFalse(properties.getHeaders().containsKey("Authorization"));
        assertEquals(PAYLOAD, new String(message.getValue().getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void failsWhenTheBrokerSendsNack() {
        confirm(false, false);
        var error = assertThrows(IllegalStateException.class, () -> publisher.publish(CommandType.EMAIL, EVENT_ID, PAYLOAD));
        assertTrue(error.getMessage().contains("nack"));
    }

    @Test
    void failsWhenTheMessageIsReturnedEvenIfTheExchangeAcknowledgesIt() {
        confirm(true, true);
        var error = assertThrows(IllegalStateException.class,
                () -> publisher.publish(CommandType.KITCHEN_TICKET, EVENT_ID, PAYLOAD));
        assertTrue(error.getMessage().contains("NO_ROUTE"));
    }

    @Test
    void propagatesConnectionFailure() {
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().completeExceptionally(new IllegalStateException("closed"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(ExecutionException.class, () -> publisher.publish(CommandType.INVOICE, EVENT_ID, PAYLOAD));
    }
}
