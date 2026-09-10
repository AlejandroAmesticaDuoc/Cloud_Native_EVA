package cl.duoc.pedidos360.bff.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityConfigTest.ProtectedTestController.class)
class SecurityConfigTest {

    private static final String PROTECTED_PATH =
            "/api/v1/security-test";

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Test
    void shouldAllowHealthWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldRejectProtectedEndpointWithoutToken()
            throws Exception {

        mockMvc.perform(get(PROTECTED_PATH)
                        .header(
                                "X-Trace-Id",
                                "trace-test-401"
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        startsWith("Bearer")
                ))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error")
                        .value("Unauthorized"))
                .andExpect(jsonPath("$.path")
                        .value(PROTECTED_PATH))
                .andExpect(jsonPath("$.traceId")
                        .value("trace-test-401"));
    }

    @Test
    void shouldRejectJwtWithoutRequiredScope()
            throws Exception {

        mockMvc.perform(get(PROTECTED_PATH)
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        "SCOPE_otro"
                                )
                        ))
                        .header(
                                "X-Trace-Id",
                                "trace-test-403"
                        ))
                .andExpect(status().isForbidden())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        containsString("insufficient_scope")
                ))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error")
                        .value("Forbidden"))
                .andExpect(jsonPath("$.message").value(
                        "El usuario no tiene permisos para realizar esta acción"
                ))
                .andExpect(jsonPath("$.path")
                        .value(PROTECTED_PATH))
                .andExpect(jsonPath("$.traceId")
                        .value("trace-test-403"));
    }

    @Test
    void shouldAllowEndpointWithRequiredScope()
            throws Exception {

        mockMvc.perform(get(PROTECTED_PATH)
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        REQUIRED_SCOPE
                                )
                        )))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string("acceso permitido"));
    }

    @Test
    void shouldConvertScopesAndRolesFromJwt() {
        Jwt jwtToken = Jwt.withTokenValue("token-prueba")
                .header("alg", "none")
                .subject("usuario-prueba")
                .claim("scp", "pedidos360.access")
                .claim("roles", List.of(
                        "ADMIN",
                        "OPERADOR",
                        "CLIENTE",
                        "AUDITOR"
                ))
                .build();

        AbstractAuthenticationToken authentication =
                jwtAuthenticationConverter.convert(jwtToken);

        assertNotNull(authentication);

        Set<String> authorities = authentication
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertTrue(authorities.containsAll(Set.of(
                "SCOPE_pedidos360.access",
                "ROLE_ADMIN",
                "ROLE_OPERADOR",
                "ROLE_CLIENTE",
                "ROLE_AUDITOR"
        )));
    }

    @RestController
    static class ProtectedTestController {

        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "acceso permitido";
        }
    }
}