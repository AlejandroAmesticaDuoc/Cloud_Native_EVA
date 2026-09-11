package cl.duoc.pedidos360.bff.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "API_DOCS_ENABLED=false")
@AutoConfigureMockMvc
class OpenApiDisabledTest {

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    private static final List<String> DOCUMENTATION_PATHS =
            List.of(
                    "/v3/api-docs",
                    "/v3/api-docs.yaml",
                    "/v3/api-docs/swagger-config",
                    "/swagger-ui.html",
                    "/swagger-ui/index.html",
                    "/swagger-ui/swagger-ui.css"
            );

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectDocumentationWithoutToken()
            throws Exception {

        for (String path : DOCUMENTATION_PATHS) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_JSON
                    ))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.path").value(path));
        }
    }

    @Test
    void shouldRejectDocumentationEvenForAdminWithScope()
            throws Exception {

        for (String path : DOCUMENTATION_PATHS) {
            mockMvc.perform(get(path)
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority(
                                            REQUIRED_SCOPE
                                    ),
                                    new SimpleGrantedAuthority(
                                            "ROLE_ADMIN"
                                    )
                            )))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_JSON
                    ))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.path").value(path));
        }
    }

    @Test
    void shouldKeepAuthenticatedApiAvailable()
            throws Exception {

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        REQUIRED_SCOPE
                                ),
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ));
    }
}