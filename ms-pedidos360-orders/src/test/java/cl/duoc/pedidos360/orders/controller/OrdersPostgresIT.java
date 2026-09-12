package cl.duoc.pedidos360.orders.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;
import cl.duoc.pedidos360.orders.dto.OrderStatus;
import cl.duoc.pedidos360.orders.security.CurrentUser;
import cl.duoc.pedidos360.orders.service.OrdersService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import cl.duoc.pedidos360.orders.messaging.NotificationOutbox;
import cl.duoc.pedidos360.orders.messaging.OutboxPublisher;
import cl.duoc.pedidos360.orders.messaging.RabbitEmailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrdersPostgresIT extends OrdersApiContract {
    @Autowired OrdersService orders;
    @Autowired NotificationOutbox outbox;
    @Autowired PlatformTransactionManager manager;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
            .withEnv("ORDERS_DB_PASSWORD", "orders-test-only")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "..", "infra", "postgres", "002-create-orders.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/002-create-orders.sh");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_orders"));
        registry.add("spring.datasource.username", () -> "pedidos360_orders");
        registry.add("spring.datasource.password", () -> "orders-test-only");
    }

    @Test
    void connectsWithAnUnprivilegedUser() {
        assertEquals("pedidos360_orders", jdbc.queryForObject("SELECT current_user", String.class));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = current_user", Boolean.class)));
    }

    @Test
    void concurrentAcceptanceCallsStockOnce() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        var start = new CountDownLatch(1);
        var admin = new CurrentUser("op", Set.of("ROLE_OPERADOR"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<OrderStatus> action = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return orders.changeStatus(id, OrderStatus.ACEPTADO, admin, "concurrent-orders").status();
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertEquals(OrderStatus.ACEPTADO, first.get(15, TimeUnit.SECONDS));
            assertEquals(OrderStatus.ACEPTADO, second.get(15, TimeUnit.SECONDS));
        }
        verify(catalog, times(1)).deduct(any(), anyString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class));
    }

    @Test
    void concurrentCancellationCallsReleaseOnce() throws Exception {
        long id = order("ana", OrderStatus.ACEPTADO).getId();
        var start = new CountDownLatch(1);
        var client = new CurrentUser("ana", Set.of("ROLE_CLIENTE"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                orders.cancel(id, client, "concurrent-cancel");
                return true;
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertTrue(first.get(15, TimeUnit.SECONDS));
            assertTrue(second.get(15, TimeUnit.SECONDS));
        }
        verify(catalog, times(1)).release(eq(id), anyString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class));
    }

    @Test
    void rollsBackStateAndNotificationTogether() {
        long id = order("ana", OrderStatus.CREADO).getId();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var stored = repository.findLocked(id).orElseThrow();
            stored.complete(OrderStatus.CANCELADO);
            outbox.enqueue(stored, "rollback-test");
            repository.flush();
            throw new IllegalStateException("rollback");
        }));
        assertEquals(OrderStatus.CREADO, repository.findById(id).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class));
    }

    @Test
    void persistsFailedPublicationAndRetriesWithoutChangingEventId() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        orders.cancel(id, new CurrentUser("ana", Set.of("ROLE_CLIENTE")), "retry-notify");
        String eventId = jdbc.queryForObject("SELECT event_id FROM notification_outbox", String.class);
        var sender = mock(RabbitEmailSender.class);
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(sender).send(anyString(), anyString());
        var publisher = new OutboxPublisher(jdbc, sender, manager);
        publisher.publishNext();
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM notification_outbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL", Integer.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT next_attempt_at > CURRENT_TIMESTAMP FROM notification_outbox", Boolean.class)));
        publisher.publishNext();
        verify(sender, times(1)).send(eq(eventId), anyString());
        jdbc.update("UPDATE notification_outbox SET next_attempt_at = CURRENT_TIMESTAMP");
        publisher.publishNext();
        publisher.publishNext();
        verify(sender, times(2)).send(eq(eventId), anyString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NOT NULL", Integer.class));
    }

    @Test
    void concurrentPublishersDoNotPublishSameLockedRow() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        orders.cancel(id, new CurrentUser("ana", Set.of("ROLE_CLIENTE")), "concurrent-publish");
        var sender = mock(RabbitEmailSender.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(sender).send(anyString(), anyString());
        var publisher = new OutboxPublisher(jdbc, sender, manager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(publisher::publishNext);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.submit(publisher::publishNext).get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        verify(sender, times(1)).send(anyString(), anyString());
    }
}
