package cl.duoc.pedidos360.mqadmin.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import cl.duoc.pedidos360.mqadmin.security.support.TestJwtIssuer;
import cl.duoc.pedidos360.mqadmin.service.RabbitAdminService;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contexto completo con tokens firmados por un emisor local: valida emisor, audiencia, expiración,
 * conversión de roles de Entra y que solo ADMIN con el scope pueda administrar RabbitMQ.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtSecurityTest {
    private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
    private static final String QUEUES = "/api/v1/mq-admin/queues";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RabbitAdminService service;

    @DynamicPropertySource
    static void jwtConfiguration(DynamicPropertyRegistry registry) {
        registry.add("JWT_ISSUER_URI", ISSUER::issuerUri);
        registry.add("JWT_AUDIENCE", () -> TestJwtIssuer.AUDIENCE);
    }

    @AfterAll
    static void closeIssuer() {
        ISSUER.close();
    }

    private static String bearer(JWTClaimsSet claims) throws Exception {
        return "Bearer " + ISSUER.sign(claims);
    }

    @Test
    void healthIsPublicAndTheRestIsDenied() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/otra-ruta").header("Authorization",
                bearer(ISSUER.claims().claim("roles", List.of("ADMIN")).build()))).andExpect(status().isForbidden());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAdminWithScope() throws Exception {
        when(service.listQueues()).thenReturn(List.of());
        mvc.perform(get(QUEUES).header("Authorization", bearer(ISSUER.claims().claim("roles", List.of("ADMIN")).build())))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "OPERADOR", "AUDITOR"})
    void rejectsOtherRoles(String role) throws Exception {
        mvc.perform(get(QUEUES).header("Authorization", bearer(ISSUER.claims().claim("roles", List.of(role)).build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("El usuario no tiene permisos para realizar esta acción"));
    }

    @Test
    void requiresTheApiScopeEvenForAdmin() throws Exception {
        mvc.perform(get(QUEUES).header("Authorization",
                        bearer(ISSUER.claims().claim("roles", List.of("ADMIN")).claim("scp", null).build())))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "missing-exp"})
    void rejectsInvalidTokens(String scenario) throws Exception {
        JWTClaimsSet.Builder claims = ISSUER.claims().claim("roles", List.of("ADMIN"));
        switch (scenario) {
            case "issuer" -> claims.issuer(ISSUER.issuerUri() + "/otro");
            case "audience" -> claims.audience("otra-api");
            case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(300)));
            case "missing-exp" -> claims.expirationTime(null);
            default -> throw new IllegalArgumentException();
        }
        mvc.perform(get(QUEUES).header("Authorization", bearer(claims.build())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
