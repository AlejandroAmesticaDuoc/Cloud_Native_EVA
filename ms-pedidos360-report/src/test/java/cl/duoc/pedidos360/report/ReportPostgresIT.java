package cl.duoc.pedidos360.report;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import cl.duoc.pedidos360.report.dto.OrderEvent.Status;
import cl.duoc.pedidos360.report.messaging.EventParserTest;
import cl.duoc.pedidos360.report.security.support.TestJwtIssuer;
import cl.duoc.pedidos360.report.service.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(properties = {"report.events.enabled=false", "API_DOCS_ENABLED=true"})
@AutoConfigureMockMvc
@Testcontainers
class ReportPostgresIT {
    private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
            .withEnv("REPORT_DB_PASSWORD", "report-test-only")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "..", "infra", "postgres", "004-create-report.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/004-create-report.sh");
    @Autowired ReportProjection projection;
    @Autowired ReportsService reports;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean Clock clock;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/postgres", "/pedidos360_report"));
        registry.add("spring.datasource.username", () -> "pedidos360_report");
        registry.add("spring.datasource.password", () -> "report-test-only");
        registry.add("JWT_ISSUER_URI", ISSUER::issuerUri);
        registry.add("JWT_AUDIENCE", () -> TestJwtIssuer.AUDIENCE);
    }

    @AfterAll static void closeIssuer() { ISSUER.close(); }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM report_events");
        jdbc.update("DELETE FROM report_orders");
        jdbc.update("DELETE FROM report_rejections");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-12T14:30:00Z"));
    }

    String event(long id, long version, String type, String before, String after) {
        return EventParserTest.event(id, version, type, before, after);
    }
    String created(long id) { return event(id, 1, "OrderCreated", null, "CREADO"); }
    String delivered(long id, long version, String at) {
        return event(id, version, "OrderStatusChanged", "DESPACHADO", "ENTREGADO")
                .replace("2026-09-12T12:00:00Z", at);
    }
    void receive(long id, String payload, long offset) {
        projection.receive(new ConsumerRecord<>("orders.events", 0, offset, String.valueOf(id), payload));
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    String token(String role) throws Exception {
        return ISSUER.sign(ISSUER.claims().claim("roles", List.of(role)).build());
    }

    @Test
    void usesOwnUnprivilegedDatabase() {
        assertEquals("pedidos360_report", jdbc.queryForObject("SELECT current_user", String.class));
        assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class));
    }

    @Test
    void emptyReportContainsZerosAndUnknownLeadTime() {
        var summary = reports.summary(24);
        assertEquals(0, summary.totalOrders());
        assertEquals(0, summary.activeOrders());
        assertEquals(new BigDecimal("0.00"), summary.deliveredAmount());
        assertEquals(6, summary.ordersByStatus().size());
        assertTrue(summary.ordersByStatus().values().stream().allMatch(n -> n == 0));
        assertEquals(24, summary.salesByHour().size());
        assertTrue(summary.salesByHour().stream().allMatch(hour -> hour.deliveredOrders() == 0));
        var lead = reports.leadTime();
        assertEquals(0, lead.deliveredOrders());
        assertNull(lead.averageSeconds());
        assertNull(lead.minimumSeconds());
        assertNull(lead.maximumSeconds());
    }

    @Test
    void projectsEveryActiveAndTerminalStatusOnce() {
        receive(1, created(1), 0);
        receive(2, event(2, 1, "OrderAccepted", "CREADO", "ACEPTADO"), 1);
        receive(3, event(3, 1, "OrderStatusChanged", "ACEPTADO", "EN_PREPARACION"), 2);
        receive(4, event(4, 1, "OrderStatusChanged", "EN_PREPARACION", "DESPACHADO"), 3);
        receive(5, delivered(5, 1, "2026-09-12T14:00:00Z"), 4);
        receive(6, event(6, 1, "OrderCancelled", "CREADO", "CANCELADO"), 5);
        var summary = reports.summary(2);
        assertEquals(6, summary.totalOrders());
        assertEquals(4, summary.activeOrders());
        assertEquals(1, summary.deliveredOrders());
        assertEquals(1, summary.cancelledOrders());
        assertTrue(summary.ordersByStatus().values().stream().allMatch(n -> n == 1));
        assertEquals(new BigDecimal("100.50"), summary.deliveredAmount());
    }

    @Test
    void countsDeliveryOnceAfterExactOrEquivalentReplay() {
        String delivery = delivered(10, 5, "2026-09-12T14:00:00Z");
        receive(10, created(10), 0);
        receive(10, delivery, 1);
        receive(10, delivery.replace("100.50", "100.5"), 2);
        assertEquals(2, count("report_events"));
        assertEquals(0, count("report_rejections"));
        assertEquals(1, reports.summary(24).totalOrders());
        assertEquals(new BigDecimal("100.50"), reports.summary(24).deliveredAmount());
        assertEquals(1, reports.leadTime().deliveredOrders());
    }

    @Test
    void olderUnseenEventsNeverRewindLatestVersion() {
        receive(10, delivered(10, 5, "2026-09-12T14:00:00Z"), 0);
        receive(10, created(10), 1);
        receive(10, event(10, 2, "OrderAccepted", "CREADO", "ACEPTADO"), 2);
        assertEquals(3, count("report_events"));
        assertEquals(1, count("report_orders"));
        assertEquals(5, jdbc.queryForObject("SELECT aggregate_version FROM report_orders", Long.class));
        assertEquals(1, reports.summary(24).ordersByStatus().get(Status.ENTREGADO));
        assertEquals(new BigDecimal("10800.000"), reports.leadTime().averageSeconds());
    }

    @Test
    void usesCreationToDeliveryDurationNotReceptionOrAcceptance() {
        receive(1, delivered(1, 1, "2026-09-12T11:30:00Z"), 0);
        receive(2, delivered(2, 1, "2026-09-12T12:30:00Z"), 1);
        receive(3, event(3, 1, "OrderAccepted", "CREADO", "ACEPTADO"), 2);
        receive(4, event(4, 1, "OrderCancelled", "ACEPTADO", "CANCELADO"), 3);
        var lead = reports.leadTime();
        assertEquals(2, lead.deliveredOrders());
        assertEquals(new BigDecimal("3600.000"), lead.averageSeconds());
        assertEquals(new BigDecimal("1800.000"), lead.minimumSeconds());
        assertEquals(new BigDecimal("5400.000"), lead.maximumSeconds());
    }

    @Test
    void preservesSubsecondDurations() {
        receive(1, delivered(1, 1, "2026-09-12T11:00:00.125Z"), 0);
        assertEquals(new BigDecimal("0.125"), reports.leadTime().averageSeconds());
    }

    @Test
    void hourlyRangeUsesUtcDeliveryTimeAndIncludesZeroBuckets() {
        receive(1, delivered(1, 1, "2026-09-12T12:59:59Z"), 0);
        receive(2, delivered(2, 1, "2026-09-12T13:00:00Z"), 1);
        receive(3, delivered(3, 1, "2026-09-12T14:59:59Z"), 2);
        receive(4, delivered(4, 1, "2026-09-12T15:00:00Z"), 3);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL TIME ZONE 'America/Santiago'");
            var summary = reports.summary(2);
            assertEquals(Instant.parse("2026-09-12T13:00:00Z"), summary.hourlyFrom());
            assertEquals(Instant.parse("2026-09-12T15:00:00Z"), summary.hourlyTo());
            assertEquals(4, summary.deliveredOrders());
            assertEquals(2, summary.salesByHour().size());
            assertTrue(summary.salesByHour().stream().allMatch(hour -> hour.deliveredOrders() == 1));
            assertEquals(new BigDecimal("100.50"), summary.salesByHour().getFirst().deliveredAmount());
        });
        assertEquals(1, reports.summary(3).salesByHour().getFirst().deliveredOrders());
        assertEquals(168, reports.summary(168).salesByHour().size());
    }

    @Test
    void rejectsConflictingIdsAndVersionsWithoutChangingKpis() {
        String first = delivered(10, 1, "2026-09-12T14:00:00Z");
        receive(10, first, 0);
        receive(10, first.replace("100.50", "200.00"), 1);
        receive(10, delivered(10, 1, "2026-09-12T14:10:00Z"), 2);
        assertEquals(1, count("report_events"));
        assertEquals(2, count("report_rejections"));
        assertEquals(new BigDecimal("100.50"), reports.summary(24).deliveredAmount());
        assertEquals(new BigDecimal("10800.000"), reports.leadTime().averageSeconds());
    }

    @Test
    void rejectsInvalidTimestampsAndPoisonMessagesThenContinues() {
        receive(1, delivered(1, 1, "2026-09-12T10:59:59Z"), 0);
        receive(1, "{\"token\":\"secret-not-stored\"}", 1);
        receive(1, "{\"token\":\"secret-not-stored\"}", 1);
        receive(1, created(1), 2);
        assertEquals(2, count("report_rejections"));
        assertEquals(1, count("report_events"));
        assertEquals(1, reports.summary(24).totalOrders());
    }

    @Test
    void inboxAndProjectionRollBackTogether() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            receive(1, created(1), 0);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, count("report_events"));
        assertEquals(0, count("report_orders"));
    }

    @Test
    void concurrentReplayAndVersionsKeepExactlyOneLatestSnapshot() throws Exception {
        String delivery = delivered(10, 5, "2026-09-12T14:00:00Z");
        String create = created(10);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var futures = List.of(create, delivery, delivery).stream().map(payload ->
                pool.submit(() -> { gate.await(); receive(10, payload, 0); return true; })).toList();
            gate.countDown();
            for (var future : futures) assertTrue(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(2, count("report_events"));
        assertEquals(1, count("report_orders"));
        assertEquals(0, count("report_rejections"));
        assertEquals(1, reports.summary(24).deliveredOrders());
        assertEquals(0, reports.summary(24).activeOrders());
    }

    @Test
    void administratorCanReadBothReportsWithTrace() throws Exception {
        for (String path : List.of("/api/v1/reports/summary", "/api/v1/reports/lead-time")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token("ADMIN")).header("X-Trace-Id", "report-http"))
                    .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", "report-http"))
                    .andExpect(jsonPath("$.deliveredOrders").value(0));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "OPERADOR", "AUDITOR", "OTHER"})
    void rejectsOtherRoles(String role) throws Exception {
        for (String path : List.of("/api/v1/reports/summary", "/api/v1/reports/lead-time")) {
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
            mvc.perform(get("/api/v1/reports/summary").header("Authorization", "Bearer " + jwt)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void requiresAuthenticationAndScopeAndDeniesWrites() throws Exception {
        mvc.perform(get("/api/v1/reports/summary")).andExpect(status().isUnauthorized());
        String noScope = ISSUER.sign(ISSUER.claims().claim("roles", List.of("ADMIN")).claim("scp", null).build());
        mvc.perform(get("/api/v1/reports/summary").header("Authorization", "Bearer " + noScope)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reports/summary").header("Authorization", "Bearer " + token("ADMIN"))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "169", "1.5", "abc"})
    void validatesHours(String hours) throws Exception {
        mvc.perform(get("/api/v1/reports/summary?hours=" + hours).header("Authorization", "Bearer " + token("ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void documentsReadOnlyReportsAndExposesConsumerReadiness() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/reports/summary'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/reports/lead-time'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/reports/summary'].post").doesNotExist());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
