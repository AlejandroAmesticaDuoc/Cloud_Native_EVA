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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrdersPostgresIT extends OrdersApiContract {
    @Autowired OrdersService orders;
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
    }
}
