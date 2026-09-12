package cl.duoc.pedidos360.bff.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import cl.duoc.pedidos360.bff.dto.audit.AuditPage;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import cl.duoc.pedidos360.bff.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class AuditControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuditService service;

    RequestPostProcessor reader(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_" + role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "AUDITOR"})
    void acceptsPrivilegedReadersAndForwardsParameters(String role) throws Exception {
        when(service.find(null, 0, 50)).thenReturn(new AuditPage(List.of(), null));
        when(service.find(10L, 5, 20)).thenReturn(new AuditPage(List.of(), null));
        mvc.perform(get("/api/v1/audit").with(reader(role))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/audit/orders/10?afterId=5&size=20").with(reader(role))).andExpect(status().isOk());
        verify(service).find(null, 0, 50);
        verify(service).find(10L, 5, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "OPERADOR"})
    void deniesOtherRoles(String role) throws Exception {
        for (String path : List.of("/api/v1/audit", "/api/v1/audit/orders/10")) {
            mvc.perform(get(path).with(reader(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void requiresAuthenticationAndScopeAndDeniesWrites() throws Exception {
        mvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/audit").with(reader("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?afterId=-1", "?afterId=1.5", "?size=0", "?size=101", "?size=abc", "/orders/0", "/orders/-1"})
    void validatesQueriesBeforeDownstream(String suffix) throws Exception {
        mvc.perform(get("/api/v1/audit" + suffix).with(reader("AUDITOR")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void preservesTraceOnUnavailableService() throws Exception {
        when(service.find(null, 0, 50)).thenThrow(new DownstreamServiceException("El servicio de auditoría no está disponible"));
        mvc.perform(get("/api/v1/audit").with(reader("AUDITOR")).header("X-Trace-Id", "audit-failure"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.traceId").value("audit-failure"))
                .andExpect(header().string("X-Trace-Id", "audit-failure"));
    }
}
