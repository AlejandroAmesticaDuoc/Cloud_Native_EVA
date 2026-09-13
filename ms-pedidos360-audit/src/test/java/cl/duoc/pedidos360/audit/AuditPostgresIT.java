package cl.duoc.pedidos360.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import cl.duoc.pedidos360.audit.messaging.EventParserTest;
import cl.duoc.pedidos360.audit.security.support.TestJwtIssuer;
import cl.duoc.pedidos360.audit.service.AuditStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(properties = {"audit.events.enabled=false", "API_DOCS_ENABLED=true"})
@AutoConfigureMockMvc
@Testcontainers
class AuditPostgresIT {
    private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
            .withEnv("AUDIT_DB_PASSWORD", "audit-test-only")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "..", "infra", "postgres", "003-create-audit.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/003-create-audit.sh");

    @Autowired AuditStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_audit"));
        registry.add("spring.datasource.username", () -> "pedidos360_audit");
        registry.add("spring.datasource.password", () -> "audit-test-only");
        registry.add("JWT_ISSUER_URI", ISSUER::issuerUri);
        registry.add("JWT_AUDIENCE", () -> TestJwtIssuer.AUDIENCE);
    }

    @AfterAll static void closeIssuer() { ISSUER.close(); }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM audit_rejections");
    }

    String created(long order) { return EventParserTest.event(order, 1, "OrderCreated", null, "CREADO"); }
    void receive(String payload, long offset) { store.receive(new ConsumerRecord<>("orders.events", 0, offset, "10", payload)); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    String token(String role) throws Exception {
        return ISSUER.sign(ISSUER.claims().claim("roles", List.of(role)).build());
    }

    @Test
    void usesUnprivilegedDatabaseUser() {
        assertEquals("pedidos360_audit", jdbc.queryForObject("SELECT current_user", String.class));
        assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class));
    }

    @Test
    void deduplicatesEventsWithoutRewritingTheirHistory() {
        String event = created(10);
        receive(event, 0);
        long id = store.find(null, 0, 50).items().getFirst().id();
        receive(event.replace("100.50", "100.5"), 1);
        assertEquals(1, count("audit_events"));
        assertEquals(0, count("audit_rejections"));
        assertEquals(id, store.find(null, 0, 50).items().getFirst().id());
    }

    @Test
    void storesLateEventsAndSupportsCursorAndOrderFilter() {
        receive(EventParserTest.event(10, 2, "OrderCancelled", "CREADO", "CANCELADO"), 0);
        receive(created(10), 1);
        store.receive(new ConsumerRecord<>("orders.events", 1, 0, "11", created(11)));
        var page = store.find(10L, 0, 1);
        assertEquals(2L, page.items().getFirst().event().aggregateVersion());
        assertNotNull(page.nextAfterId());
        var next = store.find(10L, page.nextAfterId(), 1);
        assertEquals(1L, next.items().getFirst().event().aggregateVersion());
        assertNull(next.nextAfterId());
        assertEquals(3, store.find(null, 0, 100).items().size());
        assertTrue(store.find(999L, 0, 50).items().isEmpty());
    }

    @Test
    void quarantinesConflictingIdAndOrderVersionWithoutOverwrite() {
        String first = created(10);
        receive(first, 0);
        receive(first.replace("100.50", "200.00"), 1);
        receive(created(10), 2);
        assertEquals(1, count("audit_events"));
        assertEquals(2, count("audit_rejections"));
        assertEquals("100.50", store.find(null, 0, 50).items().getFirst().event().total().toPlainString());
    }

    @Test
    void quarantinesPoisonRecordOnceAndContinues() {
        receive("{\"token\":\"secret-not-persisted\"}", 0);
        receive("{\"token\":\"secret-not-persisted\"}", 0);
        receive(created(10), 1);
        assertEquals(1, count("audit_events"));
        assertEquals(1, count("audit_rejections"));
        assertEquals("INVALID_EVENT", jdbc.queryForObject("SELECT reason FROM audit_rejections", String.class));
        assertEquals(64, jdbc.queryForObject("SELECT payload_sha256 FROM audit_rejections", String.class).length());
    }

    @Test
    void databaseTransactionRollsBackBeforeAcknowledgement() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            receive(created(10), 0);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, count("audit_events"));
    }

    @Test
    void concurrentReplayPersistsOnce() throws Exception {
        String payload = created(10);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action = () -> { gate.await(); receive(payload, 0); return true; };
            var first = pool.submit(action);
            var second = pool.submit(action);
            gate.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("audit_events"));
        assertEquals(0, count("audit_rejections"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "AUDITOR"})
    void onlyPrivilegedReadersSeeHistory(String role) throws Exception {
        receive(created(10), 0);
        for (String path : List.of("/api/v1/audit", "/api/v1/audit/orders/10")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token(role)).header("X-Trace-Id", "audit-http"))
                    .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", "audit-http"))
                    .andExpect(jsonPath("$.items[0].event.actorId").value("operator"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "OPERADOR", "OTHER"})
    void rejectsOtherRoles(String role) throws Exception {
        for (String path : List.of("/api/v1/audit", "/api/v1/audit/orders/10")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token(role))).andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "future", "missing-exp", "untrusted"})
    void rejectsInvalidJwt(String scenario) throws Exception {
        var claims = ISSUER.claims().claim("roles", List.of("ADMIN"));
        switch (scenario) {
            case "issuer" -> claims.issuer(ISSUER.issuerUri() + "/other");
            case "audience" -> claims.audience("other");
            case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(300)));
            case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(300)));
            case "missing-exp" -> claims.expirationTime(null);
        }
        try (var untrusted = new TestJwtIssuer()) {
            String jwt = (scenario.equals("untrusted") ? untrusted : ISSUER).sign(claims.build());
            mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + jwt)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void requiresAuthenticationAndScopeAndDeniesWrites() throws Exception {
        mvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
        String noScope = ISSUER.sign(ISSUER.claims().claim("roles", List.of("ADMIN")).claim("scp", null).build());
        mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + noScope)).andExpect(status().isForbidden());
        for (var request : List.of(post("/api/v1/audit"), delete("/api/v1/audit/orders/10"), put("/api/v1/audit/orders/10"))) {
            mvc.perform(request.header("Authorization", "Bearer " + token("ADMIN"))).andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"?afterId=-1", "?afterId=1.5", "?size=0", "?size=101", "?size=abc", "/orders/0", "/orders/-1"})
    void validatesQueries(String suffix) throws Exception {
        mvc.perform(get("/api/v1/audit" + suffix).header("Authorization", "Bearer " + token("AUDITOR")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void documentsReadOnlyApiAndReportsDisabledConsumerUnready() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/audit'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audit/orders/{orderId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audit'].post").doesNotExist());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
