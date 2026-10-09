package cl.duoc.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cl.duoc.pedidos360.orders.client.CatalogClient;
import cl.duoc.pedidos360.orders.dto.OrderStatus;
import cl.duoc.pedidos360.orders.entity.OrderItem;
import cl.duoc.pedidos360.orders.entity.PurchaseOrder;
import cl.duoc.pedidos360.orders.repository.OrderRepository;
import cl.duoc.pedidos360.orders.security.CurrentUser;
import cl.duoc.pedidos360.orders.service.OrdersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requiere Docker: PostgreSQL y RabbitMQ reales. Orders declara la topología del contrato, la outbox publica
 * cada comando en su exchange/routing key con publisher confirms y el envelope común llega a la cola correcta.
 */
@SpringBootTest(properties = {"orders.notifications.enabled=true", "orders.notifications.poll-delay=100"})
@Testcontainers
class OrdersRabbitIT {
    static final String VHOST = "pedidos360";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
            .withEnv("ORDERS_DB_PASSWORD", "orders-test-only")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "..", "infra", "postgres", "002-create-orders.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/002-create-orders.sh");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.5-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_VHOST", VHOST);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_orders"));
        registry.add("spring.datasource.username", () -> "pedidos360_orders");
        registry.add("spring.datasource.password", () -> "orders-test-only");
        registry.add("RABBITMQ_HOST", RABBIT::getHost);
        registry.add("RABBITMQ_PORT", RABBIT::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", RABBIT::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", RABBIT::getAdminPassword);
        registry.add("RABBITMQ_VHOST", () -> VHOST);
    }

    @Autowired OrdersService orders;
    @Autowired OrderRepository repository;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitCommandPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired @Qualifier("invoiceDirectBinding") Binding invoiceBinding;
    @MockitoBean CatalogClient catalog;
    final CurrentUser operator = new CurrentUser("op", Set.of("ROLE_OPERADOR"));

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notification_outbox");
        for (String queue : List.of("q.cmd.email", "q.cmd.kitchen", "q.cmd.invoice")) admin.purgeQueue(queue, false);
    }

    long order(OrderStatus status) {
        var order = new PurchaseOrder("ana", List.of(new OrderItem(7L, 2, new BigDecimal("1500.00")),
                new OrderItem(9L, 1, new BigDecimal("2990.00"))));
        order.complete(status);
        return repository.saveAndFlush(order).getId();
    }

    Message receive(String queue) {
        Message message = rabbit.receive(queue, 15000);
        assertNotNull(message, "no llegó un comando a " + queue);
        return message;
    }

    void assertEnvelope(Message message, long orderId, String routingKey, String traceId) {
        var properties = message.getMessageProperties();
        var body = json.readTree(message.getBody());
        assertEquals(body.get("eventId").asString(), properties.getMessageId());
        assertEquals(routingKey, properties.getType());
        assertEquals("order-" + orderId, properties.getCorrelationId());
        assertEquals("ms-pedidos360-orders", properties.getAppId());
        assertEquals("application/json", properties.getContentType());
        assertEquals(MessageDeliveryMode.PERSISTENT, properties.getReceivedDeliveryMode());
        assertEquals(traceId, properties.getHeader("x-trace-id"));
        assertEquals(1, ((Number) properties.getHeader("x-schema-version")).intValue());
        assertNotNull(properties.getTimestamp());
        assertEquals(orderId, body.get("orderId").asLong());
    }

    @Test
    void acceptancePublishesTheEmailAndTheKitchenTicketThroughCmdDirect() {
        long id = order(OrderStatus.CREADO);
        orders.changeStatus(id, OrderStatus.ACEPTADO, operator, "rabbit-accept");
        var email = receive("q.cmd.email");
        assertEnvelope(email, id, "email.send", "rabbit-accept");
        assertEquals("cmd.direct", email.getMessageProperties().getReceivedExchange());
        var ticket = receive("q.cmd.kitchen");
        assertEnvelope(ticket, id, "kitchen.ticket", "rabbit-accept");
        assertEquals(2, json.readTree(ticket.getBody()).get("items").size());
    }

    @Test
    void cancellationPublishesThePriorityEmailThroughCmdTopic() {
        long id = order(OrderStatus.CREADO);
        orders.cancel(id, new CurrentUser("ana", Set.of("ROLE_CLIENTE")), "rabbit-cancel");
        var email = receive("q.cmd.email");
        assertEnvelope(email, id, "email.send.high", "rabbit-cancel");
        assertEquals("cmd.topic", email.getMessageProperties().getReceivedExchange());
        assertEquals("email.send.high", email.getMessageProperties().getReceivedRoutingKey());
    }

    @Test
    void deliveryPublishesTheInvoiceAndMarksEveryRowAsPublished() throws Exception {
        long id = order(OrderStatus.DESPACHADO);
        orders.changeStatus(id, OrderStatus.ENTREGADO, operator, "rabbit-deliver");
        assertEnvelope(receive("q.cmd.email"), id, "email.send", "rabbit-deliver");
        var invoice = receive("q.cmd.invoice");
        assertEnvelope(invoice, id, "invoice.gen", "rabbit-deliver");
        assertEquals(0, new BigDecimal("5990.00").compareTo(json.readTree(invoice.getBody()).get("total").decimalValue()));
        for (int i = 0; i < 50 && jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL", Integer.class) > 0; i++) {
            Thread.sleep(100);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL", Integer.class));
    }

    @Test
    void failsWhenTheBrokerReturnsAnUnroutableCommand() {
        admin.removeBinding(invoiceBinding);
        try {
            var error = assertThrows(IllegalStateException.class, () -> publisher.publish(CommandType.INVOICE,
                    "9d0c8e1a-1b2c-4d3e-8f4a-5b6c7d8e9f00", """
                    {"schemaVersion":1,"eventId":"9d0c8e1a-1b2c-4d3e-8f4a-5b6c7d8e9f00","orderId":42,"occurredAt":"2026-10-08T18:00:00Z","traceId":"t"}"""));
            assertTrue(error.getMessage().contains("NO_ROUTE"), error.getMessage());
        } finally {
            admin.declareBinding(invoiceBinding);
        }
    }

    @Test
    void consumersCanRedeclareTheSameQueuesWithoutPreconditionFailures() {
        Map<String, Object> main = new HashMap<>(Map.of("x-dead-letter-exchange", "cmd.dead.dlx",
                "x-dead-letter-routing-key", "kitchen.ticket", "x-max-length", 10000L, "x-overflow", "reject-publish"));
        Map<String, Object> dlq = new HashMap<>(Map.of("x-message-ttl", 604800000, "x-max-length", 10000L));
        assertDoesNotThrow(() -> rabbit.execute(channel -> {
            channel.queueDeclare("q.cmd.kitchen", true, false, false, main);
            return channel.queueDeclare("q.cmd.kitchen.dlq", true, false, false, dlq);
        }));
    }
}
