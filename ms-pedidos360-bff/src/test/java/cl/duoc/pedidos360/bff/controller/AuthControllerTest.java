package cl.duoc.pedidos360.bff.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final String AUTH_ME_PATH =
            "/api/v1/auth/me";

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnAuthenticatedUserInformation()
            throws Exception {

        mockMvc.perform(get(AUTH_ME_PATH)
                        .with(jwt()
                                .jwt(token -> token
                                        .subject("subject-123")
                                        .claim(
                                                "oid",
                                                "object-456"
                                        )
                                        .claim(
                                                "tid",
                                                "tenant-789"
                                        )
                                        .claim(
                                                "preferred_username",
                                                "estudiante@duoc.cl"
                                        )
                                        .claim(
                                                "name",
                                                "Estudiante Duoc"
                                        ))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                REQUIRED_SCOPE
                                        ),
                                        new SimpleGrantedAuthority(
                                                "SCOPE_profile"
                                        ),
                                        new SimpleGrantedAuthority(
                                                "ROLE_CLIENTE"
                                        ),
                                        new SimpleGrantedAuthority(
                                                "ROLE_AUDITOR"
                                        )
                                )))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.userId")
                        .value("object-456"))
                .andExpect(jsonPath("$.subject")
                        .value("subject-123"))
                .andExpect(jsonPath("$.tenantId")
                        .value("tenant-789"))
                .andExpect(jsonPath("$.username")
                        .value("estudiante@duoc.cl"))
                .andExpect(jsonPath("$.displayName")
                        .value("Estudiante Duoc"))
                .andExpect(jsonPath("$.roles", hasSize(2)))
                .andExpect(jsonPath("$.roles[0]")
                        .value("AUDITOR"))
                .andExpect(jsonPath("$.roles[1]")
                        .value("CLIENTE"))
                .andExpect(jsonPath("$.scopes", hasSize(2)))
                .andExpect(jsonPath("$.scopes[0]")
                        .value("pedidos360.access"))
                .andExpect(jsonPath("$.scopes[1]")
                        .value("profile"));
    }

    @Test
    void shouldUseSubjectWhenObjectIdIsMissing()
            throws Exception {

        mockMvc.perform(get(AUTH_ME_PATH)
                        .with(jwt()
                                .jwt(token -> token
                                        .subject(
                                                "subject-fallback"
                                        ))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                REQUIRED_SCOPE
                                        )
                                )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId")
                        .value("subject-fallback"))
                .andExpect(jsonPath("$.subject")
                        .value("subject-fallback"))
                .andExpect(jsonPath("$.roles", hasSize(0)))
                .andExpect(jsonPath("$.scopes", hasSize(1)))
                .andExpect(jsonPath("$.scopes[0]")
                        .value("pedidos360.access"));
    }
}