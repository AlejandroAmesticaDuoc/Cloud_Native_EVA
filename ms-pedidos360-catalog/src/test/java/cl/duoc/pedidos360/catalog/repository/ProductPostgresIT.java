package cl.duoc.pedidos360.catalog.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

// Requiere Docker: no se omite silenciosamente cuando el motor no está disponible.
@SpringBootTest
@Testcontainers
class ProductPostgresIT extends ProductPersistenceContract {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine")
                    .withDatabaseName("postgres")
                    .withUsername("postgres")
                    .withPassword("admin-integration-only")
                    .withEnv("CATALOG_DB_PASSWORD", "catalog-integration-only")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of(
                                    "..", "infra", "postgres", "001-create-catalog.sh"
                            ).toAbsolutePath()),
                            "/docker-entrypoint-initdb.d/001-create-catalog.sh");

    @Autowired
    private JdbcTemplate postgresJdbcTemplate;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_catalog"));
        registry.add("spring.datasource.username", () -> "pedidos360_catalog");
        registry.add("spring.datasource.password", () -> "catalog-integration-only");
    }

    @Test
    void shouldConnectWithTheCatalogUserWithoutAdministrativePermissions() {
        assertEquals("pedidos360_catalog", postgresJdbcTemplate.queryForObject(
                "SELECT current_database()", String.class));
        assertEquals("pedidos360_catalog", postgresJdbcTemplate.queryForObject(
                "SELECT current_user", String.class));
        assertFalse(Boolean.TRUE.equals(postgresJdbcTemplate.queryForObject(
                "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = current_user",
                Boolean.class)));
    }
}
