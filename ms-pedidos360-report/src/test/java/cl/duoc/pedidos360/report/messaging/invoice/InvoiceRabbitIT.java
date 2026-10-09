package cl.duoc.pedidos360.report.messaging.invoice;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import cl.duoc.pedidos360.report.dto.InvoiceResponse;
import cl.duoc.pedidos360.report.exception.InvoiceNotFoundException;
import cl.duoc.pedidos360.report.service.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
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

/** Requiere Docker: PostgreSQL y RabbitMQ reales. La boleta llega por cmd.direct[invoice.gen] y queda emitida. */
@SpringBootTest(properties = {"report.events.enabled=false", "report.commands.enabled=true",
    "messaging.consumer.retry.initial-interval=100ms", "messaging.consumer.retry.max-interval=200ms"})
@Testcontainers
class InvoiceRabbitIT {
    static final String VHOST = "pedidos360";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
            .withEnv("REPORT_DB_PASSWORD", "report-test-only")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "..", "infra", "postgres", "004-create-report.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/004-create-report.sh");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.5-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_VHOST", VHOST);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_report"));
        registry.add("spring.datasource.username", () -> "pedidos360_report");
        registry.add("spring.datasource.password", () -> "report-test-only");
        registry.add("RABBITMQ_HOST", RABBIT::getHost);
        registry.add("RABBITMQ_PORT", RABBIT::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", RABBIT::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", RABBIT::getAdminPassword);
        registry.add("RABBITMQ_VHOST", () -> VHOST);
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired InvoiceService invoices;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        admin.purgeQueue("q.cmd.invoice.dlq", false);
        jdbc.update("DELETE FROM report_invoices");
    }

    InvoiceResponse awaitInvoice(long orderId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            try {
                return invoices.find(orderId);
            } catch (InvoiceNotFoundException pending) {
                Thread.sleep(100);
            }
        }
        return fail("La boleta no se emitió en 15 s");
    }

    @Test
    void consumesTheContractExampleAndIssuesTheInvoice() throws Exception {
        rabbit.send("cmd.direct", "invoice.gen", InvoiceCommandListenerTest.message(InvoiceCommandListenerTest.EXAMPLE,
                "9d0c8e1a-1b2c-4d3e-8f4a-5b6c7d8e9f00"));
        var invoice = awaitInvoice(42);
        assertEquals(new BigDecimal("5033.61"), invoice.netAmount());
        assertEquals(new BigDecimal("956.39"), invoice.taxAmount());
        assertEquals("B-0000000042", invoice.folio());
        assertEquals(0, admin.getQueueInfo("q.cmd.invoice.dlq").getMessageCount());
    }

    @Test
    void sendsAnInvoiceThatDoesNotAddUpToTheDeadLetterQueueWithoutRetries() {
        String payload = InvoiceCommandListenerTest.EXAMPLE.replace("\"total\":5990.00", "\"total\":5990.01")
                .replace("\"orderId\":42", "\"orderId\":43");
        rabbit.send("cmd.direct", "invoice.gen", InvoiceCommandListenerTest.message(payload, null));
        Message dead = rabbit.receive("q.cmd.invoice.dlq", 15000);
        assertNotNull(dead);
        assertEquals(payload, new String(dead.getBody(), StandardCharsets.UTF_8));
        var death = dead.getMessageProperties().getXDeathHeader().getFirst();
        assertEquals("q.cmd.invoice", death.get("queue"));
        assertEquals("rejected", death.get("reason"));
        assertEquals("cmd.direct", death.get("exchange"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM report_invoices", Integer.class));
    }
}
