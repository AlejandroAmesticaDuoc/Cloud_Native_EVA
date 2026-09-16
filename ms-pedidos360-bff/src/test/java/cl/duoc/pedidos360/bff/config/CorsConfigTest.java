package cl.duoc.pedidos360.bff.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties =
        "app.cors.allowed-origins=http://localhost:4200,http://127.0.0.1:4200")
@AutoConfigureMockMvc
class CorsConfigTest {

    private static final String ALLOWED_ORIGIN =
            "http://localhost:4200";

    private static final String AUTH_ME_PATH =
            "/api/v1/auth/me";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAllowPreflightFromConfiguredOrigin()
            throws Exception {

        mockMvc.perform(options("/api/v1/orders/10/status")
                        .header(
                                HttpHeaders.ORIGIN,
                                ALLOWED_ORIGIN
                        )
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                HttpMethod.PATCH.name()
                        )
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Authorization, Content-Type, X-Trace-Id"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        ALLOWED_ORIGIN
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsString("PATCH")
                ))
                .andExpect(header().exists(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_MAX_AGE,
                        "3600"
                ));
    }

    @Test
    void shouldRejectPreflightFromUnknownOrigin()
            throws Exception {

        mockMvc.perform(options(AUTH_ME_PATH)
                        .header(
                                HttpHeaders.ORIGIN,
                                "https://sitio-no-permitido.cl"
                        )
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                HttpMethod.GET.name()
                        ))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN
                ));
    }

    @Test
    void shouldIncludeCorsHeadersInUnauthorizedResponse()
            throws Exception {

        mockMvc.perform(get(AUTH_ME_PATH)
                        .header(
                                HttpHeaders.ORIGIN,
                                ALLOWED_ORIGIN
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        ALLOWED_ORIGIN
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString("WWW-Authenticate")
                ));
    }

    @Test
    void shouldAllowAuthenticatedRequestFromConfiguredOrigin()
            throws Exception {

        mockMvc.perform(get(AUTH_ME_PATH)
                        .header(
                                HttpHeaders.ORIGIN,
                                ALLOWED_ORIGIN
                        )
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        "SCOPE_pedidos360.access"
                                )
                        )))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        ALLOWED_ORIGIN
                ))
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS
                ));
    }
}