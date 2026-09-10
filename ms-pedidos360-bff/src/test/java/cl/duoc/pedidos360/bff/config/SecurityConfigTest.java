package cl.duoc.pedidos360.bff.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityConfigTest.ProtectedTestController.class)
class SecurityConfigTest {

    private static final String PROTECTED_PATH =
            "/api/v1/security-test";

    @Autowired
    private MockMvc mockMvc;

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
    void shouldAllowProtectedEndpointWithJwt()
            throws Exception {

        mockMvc.perform(get(PROTECTED_PATH)
                        .with(jwt().jwt(token -> token
                                .subject("usuario-prueba")
                        )))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string("acceso permitido"));
    }

    @RestController
    static class ProtectedTestController {

        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "acceso permitido";
        }
    }
}