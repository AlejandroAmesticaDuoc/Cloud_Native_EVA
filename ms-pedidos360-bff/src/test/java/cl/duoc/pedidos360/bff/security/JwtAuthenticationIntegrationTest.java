package cl.duoc.pedidos360.bff.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import cl.duoc.pedidos360.bff.security.support.TestJwtIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtAuthenticationIntegrationTest {

    private static final String AUTH_ME_PATH =
            "/api/v1/auth/me";

    private static final TestJwtIssuer TEST_ISSUER =
            new TestJwtIssuer();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureJwtProperties(
            DynamicPropertyRegistry registry) {

        registry.add(
                "JWT_ISSUER_URI",
                TEST_ISSUER::issuerUri
        );

        registry.add(
                "JWT_AUDIENCE",
                () -> TestJwtIssuer.AUDIENCE
        );
    }

    @AfterAll
    static void stopTestIssuer() {
        TEST_ISSUER.close();
    }

    @Test
    void shouldAuthenticateValidSignedToken()
            throws Exception {

        String token = TEST_ISSUER.sign(
                TEST_ISSUER.claims().build()
        );

        mockMvc.perform(get(AUTH_ME_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId")
                        .value("cliente-prueba"))
                .andExpect(jsonPath("$.subject")
                        .value("subject-cliente"))
                .andExpect(jsonPath("$.roles[0]")
                        .value("CLIENTE"))
                .andExpect(jsonPath("$.scopes[0]")
                        .value("pedidos360.access"));
    }
    @Test
    void shouldRejectAlteredSignature()
            throws Exception {

        String token = TEST_ISSUER.sign(
                TEST_ISSUER.claims().build()
        );

        String[] parts = token.split("\\.");

        byte[] signature = Base64.getUrlDecoder()
                .decode(parts[2]);

        signature[0] ^= 1;

        parts[2] = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(signature);

        String alteredToken = String.join(".", parts);

        assertUnauthorized(alteredToken);
    }

    @Test
    void shouldRejectExpiredToken()
            throws Exception {

        Instant now = Instant.now();

        String token = TEST_ISSUER.sign(
                TEST_ISSUER.claims()
                        .issueTime(Date.from(
                                now.minusSeconds(900)
                        ))
                        .notBeforeTime(Date.from(
                                now.minusSeconds(900)
                        ))
                        .expirationTime(Date.from(
                                now.minusSeconds(300)
                        ))
                        .build()
        );

        assertUnauthorized(token);
    }

    @Test
    void shouldRejectTokenNotYetValid()
            throws Exception {

        Instant now = Instant.now();

        String token = TEST_ISSUER.sign(
                TEST_ISSUER.claims()
                        .notBeforeTime(Date.from(
                                now.plusSeconds(300)
                        ))
                        .expirationTime(Date.from(
                                now.plusSeconds(900)
                        ))
                        .build()
        );

        assertUnauthorized(token);
    }

    @Test
    void shouldRejectIncorrectIssuer()
            throws Exception {

        String token = TEST_ISSUER.sign(
                TEST_ISSUER.claims()
                        .issuer(
                                TEST_ISSUER.issuerUri()
                                        + "/otro-emisor"
                        )
                        .build()
        );

        assertUnauthorized(token);
    }

    @Test
    void shouldRejectIncorrectAudience()
            throws Exception {

        String token = TEST_ISSUER.sign(
                TEST_ISSUER.claims()
                        .audience("otra-api")
                        .build()
        );

        assertUnauthorized(token);
    }

    private void assertUnauthorized(String token)
            throws Exception {

        mockMvc.perform(get(AUTH_ME_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        containsString("invalid_token")
                ))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(
                        "Se requiere autenticación para acceder a este recurso"
                ))
                .andExpect(jsonPath("$.path")
                        .value(AUTH_ME_PATH));
    }    
}