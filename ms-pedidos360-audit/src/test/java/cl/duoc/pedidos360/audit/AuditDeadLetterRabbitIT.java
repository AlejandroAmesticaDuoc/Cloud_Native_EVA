package cl.duoc.pedidos360.audit;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import cl.duoc.pedidos360.audit.dto.DeadLetterEntry;
import cl.duoc.pedidos360.audit.service.DeadLetterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Requiere Docker: un comando rechazado por su consumidor (nack sin requeue) llega a su DLQ y, por el binding
 * extra de cmd.dead.dlx, a q.audit.dead-letters, donde Audit lo registra con los datos reales de x-death.
 */
@SpringBootTest(properties = {"audit.events.enabled=false", "audit.dead-letters.enabled=true",
    "messaging.consumer.retry.initial-interval=100ms", "messaging.consumer.retry.max-interval=200ms"})
@Testcontainers
class AuditDeadLetterRabbitIT {
    static final String VHOST = "pedidos360";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
            .withEnv("AUDIT_DB_PASSWORD", "audit-test-only")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "..", "infra", "postgres", "003-create-audit.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/003-create-audit.sh");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.5-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_VHOST", VHOST);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_audit"));
        registry.add("spring.datasource.username", () -> "pedidos360_audit");
        registry.add("spring.datasource.password", () -> "audit-test-only");
        registry.add("RABBITMQ_HOST", RABBIT::getHost);
        registry.add("RABBITMQ_PORT", RABBIT::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", RABBIT::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", RABBIT::getAdminPassword);
        registry.add("RABBITMQ_VHOST", () -> VHOST);
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired DeadLetterStore store;
    @Autowired JdbcTemplate jdbc;

    /** Ruta de correo como la declaran orders y notify (mismos argumentos y tipos). */
    @BeforeEach
    void declareEmailRouteAndClean() {
        DirectExchange direct = ExchangeBuilder.directExchange("cmd.direct").durable(true).build();
        DirectExchange deadLetter = ExchangeBuilder.directExchange("cmd.dead.dlx").durable(true).build();
        var queue = QueueBuilder.durable("q.cmd.email").deadLetterExchange("cmd.dead.dlx")
                .deadLetterRoutingKey("email.send").maxLength(10000L).overflow(QueueBuilder.Overflow.rejectPublish).build();
        var dlq = QueueBuilder.durable("q.cmd.email.dlq").ttl(604800000).maxLength(10000L).build();
        admin.declareExchange(direct);
        admin.declareExchange(deadLetter);
        admin.declareQueue(queue);
        admin.declareQueue(dlq);
        admin.declareBinding(BindingBuilder.bind(queue).to(direct).with("email.send"));
        admin.declareBinding(BindingBuilder.bind(dlq).to(deadLetter).with("email.send"));
        for (String name : new String[] {"q.cmd.email", "q.cmd.email.dlq", "q.audit.dead-letters.dlq"}) {
            admin.purgeQueue(name, false);
        }
        jdbc.update("DELETE FROM audit_dead_letters");
    }

    static <T> T await(Supplier<T> supplier) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            T value = supplier.get();
            if (value != null) return value;
            Thread.sleep(100);
        }
        return fail("No se cumplió la condición en 15 s");
    }

    @Test
    void auditsARejectedCommandWhileItsDeadLetterQueueKeepsTheMessage() throws Exception {
        Message command = MessageBuilder.withBody("{\"orderId\":42}".getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setMessageId("event-rabbit-1").setType("email.send")
                .setCorrelationId("order-42").setHeader("x-trace-id", "trace-demo-001").build();
        rabbit.send("cmd.direct", "email.send", command);
        // Simula a notify rechazando el comando: nack sin requeue -> cmd.dead.dlx.
        rabbit.execute(channel -> {
            var delivery = channel.basicGet("q.cmd.email", false);
            assertNotNull(delivery);
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });
        DeadLetterEntry entry = await(() -> store.find(0, 10).items().stream().findFirst().orElse(null));
        assertEquals("event-rabbit-1", entry.messageId());
        assertEquals("q.cmd.email", entry.sourceQueue());
        assertEquals("rejected", entry.reason());
        assertEquals(1, entry.deathCount());
        assertEquals("cmd.direct", entry.originalExchange());
        assertEquals("email.send", entry.originalRoutingKey());
        assertEquals("email.send", entry.messageType());
        assertEquals("order-42", entry.correlationId());
        assertEquals("trace-demo-001", entry.traceId());
        assertEquals("{\"orderId\":42}", entry.payload());
        assertNotNull(entry.firstDeathAt());
        Message kept = rabbit.receive("q.cmd.email.dlq", 5000);
        assertNotNull(kept, "la DLQ de la ruta conserva el mensaje para reproceso");
        assertEquals("event-rabbit-1", kept.getMessageProperties().getMessageId());
        assertEquals(1, store.find(0, 10).items().size());
    }

    @Test
    void sendsMessagesThatAreNotDeadLettersToTheAuditDeadLetterQueue() {
        rabbit.send("", "q.audit.dead-letters", MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setMessageId("not-a-dead-letter").build());
        Message rejected = rabbit.receive("q.audit.dead-letters.dlq", 15000);
        assertNotNull(rejected);
        assertEquals("not-a-dead-letter", rejected.getMessageProperties().getMessageId());
        Map<String, ?> death = rejected.getMessageProperties().getXDeathHeader().getFirst();
        assertEquals("q.audit.dead-letters", death.get("queue"));
        assertEquals("rejected", death.get("reason"));
        assertTrue(store.find(0, 10).items().isEmpty());
    }
}
