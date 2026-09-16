package cl.duoc.pedidos360.catalog.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import cl.duoc.pedidos360.catalog.entity.Product;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// El mismo contrato se ejecuta con H2 y, en el perfil de integración, PostgreSQL real.
@Transactional
abstract class ProductPersistenceContract {

    @Autowired
    private ProductRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAndReadProduct() {
        Product product = repository.saveAndFlush(
                new Product("Café", new BigDecimal("2500.50"), 20));
        entityManager.clear();

        Product stored = repository.findById(product.getId()).orElseThrow();
        assertNotNull(stored.getId());
        assertEquals("Café", stored.getName());
        assertEquals(0, new BigDecimal("2500.50").compareTo(stored.getPrice()));
        assertEquals(20, stored.getStock());
        assertTrue(stored.isActive());
    }

    @Test
    void shouldAcceptContractLimits() {
        Product product = repository.saveAndFlush(new Product(
                "a".repeat(100), new BigDecimal("9999999999.99"), 0));
        entityManager.clear();

        Product stored = repository.findById(product.getId()).orElseThrow();
        assertEquals(100, stored.getName().length());
        assertEquals(new BigDecimal("9999999999.99"), stored.getPrice());
        assertEquals(0, stored.getStock());
    }

    @Test
    void shouldListOnlyActiveProductsInIdOrder() {
        Product first = repository.saveAndFlush(new Product("Uno", BigDecimal.ONE, 1));
        Product inactive = new Product("Inactivo", BigDecimal.ONE, 1);
        inactive.deactivate();
        repository.saveAndFlush(inactive);
        Product last = repository.saveAndFlush(new Product("Dos", BigDecimal.ONE, 1));

        assertEquals(java.util.List.of(first.getId(), last.getId()),
                repository.findByActiveTrueOrderByIdAsc().stream().map(Product::getId).toList());
        assertTrue(repository.findByIdAndActiveTrue(inactive.getId()).isEmpty());
    }

    @Test
    void shouldDeactivateWithoutDeletingTheRow() {
        Product product = repository.saveAndFlush(new Product("Café", BigDecimal.ONE, 5));
        product.deactivate();
        repository.flush();
        entityManager.clear();

        assertFalse(repository.findById(product.getId()).orElseThrow().isActive());
        assertTrue(repository.findByIdAndActiveTrue(product.getId()).isEmpty());
    }

    @Test
    void shouldUpdateValuesAndVersion() {
        Product product = repository.saveAndFlush(new Product("Café", BigDecimal.ONE, 5));
        long originalVersion = product.getVersion();
        product.updateDetails("Café grande", new BigDecimal("3000.00"));
        product.updateStock(0);
        repository.flush();
        entityManager.clear();

        Product stored = repository.findById(product.getId()).orElseThrow();
        assertEquals("Café grande", stored.getName());
        assertEquals(new BigDecimal("3000.00"), stored.getPrice());
        assertEquals(0, stored.getStock());
        assertTrue(stored.getVersion() > originalVersion);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO products (name, price, stock) VALUES ('Inválido', 0, 1)",
            "INSERT INTO products (name, price, stock) VALUES ('Inválido', 1, -1)",
            "INSERT INTO products (name, price, stock) VALUES ('   ', 1, 1)",
            "INSERT INTO products (name, price, stock) VALUES (NULL, 1, 1)"
    })
    void shouldEnforceDatabaseConstraintsWithoutDtoValidation(String sql) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(sql));
    }
}
