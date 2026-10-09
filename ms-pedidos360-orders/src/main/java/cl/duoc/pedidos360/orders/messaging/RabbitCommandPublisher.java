package cl.duoc.pedidos360.orders.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import cl.duoc.pedidos360.orders.config.MessagingProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica un comando de la outbox en su exchange/routing key y espera la confirmación del broker.
 * Falla (y OutboxPublisher reintenta) si no hay confirmación en 5 s, si el broker responde nack
 * (por ejemplo, cola llena con reject-publish) o si el mensaje vuelve sin ruta (mandatory).
 */
@Component
@ConditionalOnProperty(name = "orders.notifications.enabled", havingValue = "true")
public class RabbitCommandPublisher {
    public static final String TRACE_ID_HEADER = "x-trace-id";
    public static final String SCHEMA_VERSION_HEADER = "x-schema-version";
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;
    private final RabbitTemplate rabbit;
    private final MessagingProperties messaging;
    private final JsonMapper json;
    private final String appId;

    public RabbitCommandPublisher(RabbitTemplate rabbit, MessagingProperties messaging, JsonMapper json,
            @Value("${spring.application.name}") String appId) {
        this.rabbit = rabbit;
        this.messaging = messaging;
        this.json = json;
        this.appId = appId;
    }

    public void publish(CommandType type, String eventId, String payload) throws Exception {
        Destination destination = destination(type);
        Message message = envelope(destination.routingKey(), eventId, payload);
        var correlation = new CorrelationData(eventId);
        rabbit.send(destination.exchange(), destination.routingKey(), message, correlation);
        var confirmation = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!confirmation.ack()) {
            throw new IllegalStateException("RabbitMQ rechazó el comando (nack): " + confirmation.reason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ devolvió el comando sin cola de destino: "
                    + correlation.getReturned().getReplyText());
        }
    }

    /** Ruteo por tipo: todo sale de MessagingProperties, sin nombres escritos en el código. */
    Destination destination(CommandType type) {
        var exchanges = messaging.exchanges();
        var routes = messaging.routes();
        return switch (type) {
            case EMAIL -> new Destination(exchanges.direct(), routes.email().routingKey());
            case EMAIL_PRIORITY -> new Destination(exchanges.topic(), routes.email().priorityRoutingKey());
            case KITCHEN_TICKET -> new Destination(exchanges.direct(), routes.kitchen().routingKey());
            case INVOICE -> new Destination(exchanges.direct(), routes.invoice().routingKey());
        };
    }

    /** Envelope común: messageId=eventId, type=routing key, correlationId=order-&lt;id&gt;, x-trace-id y x-schema-version. */
    private Message envelope(String routingKey, String eventId, String payload) {
        JsonNode body = json.readTree(payload);
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(eventId)
                .setType(routingKey)
                .setCorrelationId("order-" + body.get("orderId").asLong())
                .setTimestamp(Date.from(Instant.parse(body.get("occurredAt").asString())))
                .setAppId(appId)
                .setHeader(TRACE_ID_HEADER, body.get("traceId").asString())
                .setHeader(SCHEMA_VERSION_HEADER, body.get("schemaVersion").asInt())
                .build();
    }

    record Destination(String exchange, String routingKey) {}
}
