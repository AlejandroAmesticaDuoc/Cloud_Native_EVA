package cl.duoc.pedidos360.catalog.messaging.kitchen;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.catalog.dto.KitchenTicketResponse;
import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.exception.KitchenTicketNotFoundException;
import cl.duoc.pedidos360.catalog.repository.ProductRepository;
import cl.duoc.pedidos360.catalog.service.KitchenTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Requiere Docker: RabbitMQ real (H2 para la base). El ticket llega por cmd.direct[kitchen.ticket] y queda guardado. */
@SpringBootTest(properties = {"catalog.commands.enabled=true",
    "messaging.consumer.retry.initial-interval=100ms", "messaging.consumer.retry.max-interval=200ms"})
@ActiveProfiles("test")
@Testcontainers
class KitchenTicketRabbitIT {
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
    @Autowired KitchenTicketService tickets;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        admin.purgeQueue("q.cmd.kitchen.dlq", false);
        jdbc.update("DELETE FROM kitchen_tickets");
        jdbc.update("DELETE FROM products");
    }

    Message command(String payload, String eventId) {
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8)).setContentType("application/json")
                .setContentEncoding("UTF-8").setMessageId(eventId).setType("kitchen.ticket")
                .setCorrelationId("order-77").setHeader("x-trace-id", "trace-kitchen").build();
    }

    KitchenTicketResponse awaitTicket(long orderId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            try {
                return tickets.find(orderId);
            } catch (KitchenTicketNotFoundException pending) {
                Thread.sleep(100);
            }
        }
        return fail("El ticket no se generó en 15 s");
    }

    @Test
    void consumesTheKitchenTicketAndStoresItWithCatalogNames() throws Exception {
        long coffee = products.saveAndFlush(new Product("Café grande", new BigDecimal("1500.00"), 5)).getId();
        String eventId = UUID.randomUUID().toString();
        rabbit.send("cmd.direct", "kitchen.ticket", command("""
                {"schemaVersion":1,"eventId":"%s","orderId":77,"customerId":"cliente-demo",\
                "items":[{"productId":%d,"quantity":2}],"occurredAt":"2026-10-08T15:30:00Z","traceId":"trace-kitchen"}"""
                .formatted(eventId, coffee), eventId));
        var ticket = awaitTicket(77);
        assertTrue(ticket.ticket().contains("2 x Café grande"), ticket.ticket());
        assertEquals(0, admin.getQueueInfo("q.cmd.kitchen.dlq").getMessageCount());
    }

    @Test
    void sendsAnInvalidTicketToTheKitchenDeadLetterQueue() {
        rabbit.send("cmd.direct", "kitchen.ticket", command("{\"orderId\":78}", "invalid-ticket"));
        Message dead = rabbit.receive("q.cmd.kitchen.dlq", 15000);
        assertNotNull(dead);
        assertEquals("invalid-ticket", dead.getMessageProperties().getMessageId());
        var death = dead.getMessageProperties().getXDeathHeader().getFirst();
        assertEquals("q.cmd.kitchen", death.get("queue"));
        assertEquals("rejected", death.get("reason"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kitchen_tickets", Integer.class));
    }
}
