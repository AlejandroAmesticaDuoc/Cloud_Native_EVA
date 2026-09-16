package cl.duoc.pedidos360.catalog.controller;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import cl.duoc.pedidos360.catalog.dto.StockDeductionRequest;
import cl.duoc.pedidos360.catalog.exception.StockConflictException;
import cl.duoc.pedidos360.catalog.service.StockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogPostgresApiIT extends CatalogApiContract {
    @Autowired private StockService stockService;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("postgres").withUsername("postgres").withPassword("admin-integration-only")
            .withEnv("CATALOG_DB_PASSWORD", "catalog-integration-only")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "..", "infra", "postgres", "001-create-catalog.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/001-create-catalog.sh");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_catalog"));
        registry.add("spring.datasource.username", () -> "pedidos360_catalog");
        registry.add("spring.datasource.password", () -> "catalog-integration-only");
    }

    @Test
    void shouldNotOversellWithConcurrentOrders() throws Exception {
        long id = product(10).getId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryDeduction(start, 9001, id, 7));
            var second = executor.submit(() -> tryDeduction(start, 9002, id, 7));
            start.countDown();
            int successes = first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS);
            assertEquals(1, successes);
        }
        assertEquals(3, products.findById(id).orElseThrow().getStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_deductions", Integer.class));
    }

    @Test
    void shouldNotApplyTheSameConcurrentOrderTwice() throws Exception {
        long id = product(10).getId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryDeduction(start, 9003, id, 3));
            var second = executor.submit(() -> tryDeduction(start, 9003, id, 3));
            start.countDown();
            assertTrue(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS) >= 1);
        }
        stockService.deduct(new StockDeductionRequest(9003L, List.of(new StockDeductionRequest.Item(id, 3))));
        assertEquals(7, products.findById(id).orElseThrow().getStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_deductions", Integer.class));
    }

    private int tryDeduction(CountDownLatch start, long orderId, long productId, int quantity) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            stockService.deduct(new StockDeductionRequest(orderId,
                    List.of(new StockDeductionRequest.Item(productId, quantity))));
            return 1;
        } catch (StockConflictException | DataIntegrityViolationException conflict) {
            return 0;
        }
    }

    @Test
    void identicalConcurrentOrdersSucceedEvenWhenTheFirstConsumesAllStock() throws Exception {
        long id = product(3).getId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryDeduction(start, 9010, id, 3));
            var second = executor.submit(() -> tryDeduction(start, 9010, id, 3));
            start.countDown();
            assertEquals(2, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(0, products.findById(id).orElseThrow().getStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_deductions", Integer.class));
    }
}
