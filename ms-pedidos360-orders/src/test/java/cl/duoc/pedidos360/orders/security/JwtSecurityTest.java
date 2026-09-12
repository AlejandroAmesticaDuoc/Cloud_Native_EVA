package cl.duoc.pedidos360.orders.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import cl.duoc.pedidos360.orders.security.support.TestJwtIssuer;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtSecurityTest {
    private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
    @Autowired private MockMvc mvc;

    @DynamicPropertySource
    static void jwtConfiguration(DynamicPropertyRegistry registry) {
        registry.add("JWT_ISSUER_URI", ISSUER::issuerUri);
        registry.add("JWT_AUDIENCE", () -> TestJwtIssuer.AUDIENCE);
    }

    @AfterAll static void closeIssuer() { ISSUER.close(); }

    @Test
    void shouldAcceptSignedTokenWithTheRequiredScope() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + ISSUER.sign(ISSUER.claims().build())))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "future", "missing-exp"})
    void shouldRejectInvalidClaims(String scenario) throws Exception {
        JWTClaimsSet.Builder claims = ISSUER.claims();
        switch (scenario) {
            case "issuer" -> claims.issuer(ISSUER.issuerUri() + "/otro");
            case "audience" -> claims.audience("otra-api");
            case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(300)));
            case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(300)));
            case "missing-exp" -> claims.expirationTime(null);
            default -> throw new IllegalArgumentException();
        }
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + ISSUER.sign(claims.build())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectTokenSignedByAnUntrustedKey() throws Exception {
        try (TestJwtIssuer attacker = new TestJwtIssuer()) {
            String token = attacker.sign(ISSUER.claims().build());
            mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void shouldMapEntraRolesAndRequireScopeForAdmin() throws Exception {
        String withoutScope = ISSUER.sign(ISSUER.claims().claim("roles", List.of("ADMIN")).claim("scp", null).build());
        mvc.perform(get("/api/v1/orders/999999").header("Authorization", "Bearer " + withoutScope))
                .andExpect(status().isForbidden());
        String admin = ISSUER.sign(ISSUER.claims().claim("roles", List.of("ADMIN")).build());
        mvc.perform(get("/api/v1/orders/999999").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }
}
