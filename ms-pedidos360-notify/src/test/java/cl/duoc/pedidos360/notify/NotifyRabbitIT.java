package cl.duoc.pedidos360.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import cl.duoc.pedidos360.notify.dto.EmailCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Requiere Docker: RabbitMQ real con la topología del contrato; SMTP simulado. */
@SpringBootTest(properties = {
    "management.health.mail.enabled=false", "management.endpoint.health.group.readiness.include=readinessState,rabbit",
    "messaging.consumer.retry.initial-interval=100ms", "messaging.consumer.retry.max-interval=200ms"
})
@Testcontainers
class NotifyRabbitIT {
    static final String VHOST = "pedidos360";
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.5-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_VHOST", VHOST);

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("RABBITMQ_HOST", RABBIT::getHost);
        registry.add("RABBITMQ_PORT", RABBIT::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", RABBIT::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", RABBIT::getAdminPassword);
        registry.add("RABBITMQ_VHOST", () -> VHOST);
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired JsonMapper json;
    @MockitoBean JavaMailSender sender;

    @BeforeEach
    void emptyQueues() {
        admin.purgeQueue("q.cmd.email", false);
        admin.purgeQueue("q.cmd.email.dlq", false);
    }

    Message command(EmailCommand.OrderStatus status, String routingKey) {
        var command = new EmailCommand(1, UUID.randomUUID(), 42L, "cliente-demo", status,
                Instant.parse("2026-10-08T15:30:00Z"), "trace-demo-001");
        return envelope(json.writeValueAsBytes(command), command.eventId().toString(), routingKey);
    }

    Message envelope(byte[] body, String messageId, String routingKey) {
        return MessageBuilder.withBody(body).setContentType("application/json").setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setMessageId(messageId).setType(routingKey)
                .setCorrelationId("order-42").setAppId("ms-pedidos360-orders")
                .setHeader("x-trace-id", "trace-demo-001").setHeader("x-schema-version", 1).build();
    }

    /** Detiene el consumidor: un mensaje sin ack volvería a la cola, así que una cola vacía prueba el ack. */
    void assertAcknowledgedAndNothingDeadLettered() {
        var container = listeners.getListenerContainer("notify-email");
        container.stop();
        try {
            assertEquals(0, admin.getQueueInfo("q.cmd.email").getMessageCount());
            assertEquals(0, admin.getQueueInfo("q.cmd.email.dlq").getMessageCount());
        } finally {
            container.start();
        }
    }

    JsonNode management(String path) throws Exception {
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        var request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + "/api/" + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8))).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return json.readTree(response.body());
        }
    }

    @Test
    void declaresTheContractTopologyWithItsArguments() throws Exception {
        var queue = management("queues/" + VHOST + "/q.cmd.email");
        assertTrue(queue.get("durable").asBoolean());
        assertEquals("cmd.dead.dlx", queue.get("arguments").get("x-dead-letter-exchange").asString());
        assertEquals("email.send", queue.get("arguments").get("x-dead-letter-routing-key").asString());
        assertEquals(10000, queue.get("arguments").get("x-max-length").asLong());
        assertEquals("reject-publish", queue.get("arguments").get("x-overflow").asString());
        var dlq = management("queues/" + VHOST + "/q.cmd.email.dlq");
        assertTrue(dlq.get("durable").asBoolean());
        assertEquals(604800000L, dlq.get("arguments").get("x-message-ttl").asLong());
        assertEquals(10000, dlq.get("arguments").get("x-max-length").asLong());
        assertEquals("direct", management("exchanges/" + VHOST + "/cmd.direct").get("type").asString());
        assertEquals("topic", management("exchanges/" + VHOST + "/cmd.topic").get("type").asString());
        assertEquals("direct", management("exchanges/" + VHOST + "/cmd.dead.dlx").get("type").asString());
        Set<String> bindings = StreamSupport.stream(management("queues/" + VHOST + "/q.cmd.email/bindings").spliterator(), false)
                .map(binding -> binding.get("source").asString() + "|" + binding.get("routing_key").asString())
                .collect(Collectors.toSet());
        assertTrue(bindings.containsAll(Set.of("cmd.direct|email.send", "cmd.topic|email.#")), bindings.toString());
        assertTrue(StreamSupport.stream(management("queues/" + VHOST + "/q.cmd.email.dlq/bindings").spliterator(), false)
                .anyMatch(binding -> binding.get("source").asString().equals("cmd.dead.dlx")
                        && binding.get("routing_key").asString().equals("email.send")));
        // orders declara la misma cola con estos tipos (Long e Integer): una diferencia sería 406 PRECONDITION_FAILED.
        Map<String, Object> mainArguments = new HashMap<>(Map.of("x-dead-letter-exchange", "cmd.dead.dlx",
                "x-dead-letter-routing-key", "email.send", "x-max-length", 10000L, "x-overflow", "reject-publish"));
        Map<String, Object> dlqArguments = new HashMap<>(Map.of("x-message-ttl", 604800000, "x-max-length", 10000L));
        assertDoesNotThrow(() -> rabbit.execute(channel -> {
            channel.queueDeclare("q.cmd.email", true, false, false, mainArguments);
            return channel.queueDeclare("q.cmd.email.dlq", true, false, false, dlqArguments);
        }));
    }

    @Test
    void processesAValidCommandFromTheDirectExchangeAndAcknowledgesIt() {
        rabbit.send("cmd.direct", "email.send", command(EmailCommand.OrderStatus.ACEPTADO, "email.send"));
        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(10000)).send(mail.capture());
        assertTrue(mail.getValue().getSubject().contains("#42 | ACEPTADO"));
        assertAcknowledgedAndNothingDeadLettered();
    }

    @Test
    void processesThePriorityCommandFromTheTopicExchange() {
        rabbit.send("cmd.topic", "email.send.high", command(EmailCommand.OrderStatus.CANCELADO, "email.send.high"));
        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(10000)).send(mail.capture());
        assertTrue(mail.getValue().getSubject().contains("#42 | CANCELADO"));
        assertAcknowledgedAndNothingDeadLettered();
    }

    @Test
    void sendsAnInvalidMessageToTheDeadLetterQueueWithReasonRejected() {
        String messageId = UUID.randomUUID().toString();
        rabbit.send("cmd.direct", "email.send", envelope("not-json".getBytes(StandardCharsets.UTF_8), messageId, "email.send"));
        Message dead = rabbit.receive("q.cmd.email.dlq", 15000);
        assertNotNull(dead, "el mensaje inválido debía llegar a la DLQ");
        assertEquals(messageId, dead.getMessageProperties().getMessageId());
        var death = dead.getMessageProperties().getXDeathHeader().getFirst();
        assertEquals("rejected", death.get("reason"));
        assertEquals("q.cmd.email", death.get("queue"));
        assertEquals("cmd.direct", death.get("exchange"));
        assertEquals(List.of("email.send"), death.get("routing-keys"));
        verifyNoInteractions(sender);
    }

    @Test
    void exhaustsLocalRetriesOnSmtpFailureAndThenDeadLettersTheCommand() {
        doThrow(new MailSendException("SMTP no disponible")).when(sender).send(any(SimpleMailMessage.class));
        var message = command(EmailCommand.OrderStatus.ACEPTADO, "email.send");
        rabbit.send("cmd.direct", "email.send", message);
        Message dead = rabbit.receive("q.cmd.email.dlq", 15000);
        assertNotNull(dead, "el comando debía llegar a la DLQ tras agotar los reintentos");
        assertEquals(message.getMessageProperties().getMessageId(), dead.getMessageProperties().getMessageId());
        assertEquals("rejected", dead.getMessageProperties().getXDeathHeader().getFirst().get("reason"));
        verify(sender, times(3)).send(any(SimpleMailMessage.class));
    }
}
