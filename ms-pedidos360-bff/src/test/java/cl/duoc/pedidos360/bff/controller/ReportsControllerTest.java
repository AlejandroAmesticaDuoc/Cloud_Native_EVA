package cl.duoc.pedidos360.bff.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import cl.duoc.pedidos360.bff.dto.report.*;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import cl.duoc.pedidos360.bff.service.ReportsService;
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
class ReportsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReportsService service;

    RequestPostProcessor reader(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void administratorCanReadReportsAndForwardHours() throws Exception {
        when(service.summary(24)).thenReturn(new SummaryResponse(0, 0, 0, 0, Map.of(), BigDecimal.ZERO,
                Instant.parse("2026-09-11T15:00:00Z"), Instant.parse("2026-09-12T15:00:00Z"), List.of()));
        when(service.leadTime()).thenReturn(new LeadTimeResponse(0L, null, null, null));
        mvc.perform(get("/api/v1/reports/summary").with(reader("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(0));
        mvc.perform(get("/api/v1/reports/summary?hours=2").with(reader("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/reports/lead-time").with(reader("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredOrders").value(0));
        verify(service).summary(24);
        verify(service).summary(2);
        verify(service).leadTime();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "OPERADOR", "AUDITOR"})
    void deniesOtherRoles(String role) throws Exception {
        for (String path : List.of("/api/v1/reports/summary", "/api/v1/reports/lead-time")) {
            mvc.perform(get(path).with(reader(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void requiresAuthenticationAndScopeAndDeniesWrites() throws Exception {
        mvc.perform(get("/api/v1/reports/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports/summary").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reports/summary").with(reader("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "169", "1.5", "abc"})
    void validatesWindowBeforeCallingReport(String hours) throws Exception {
        mvc.perform(get("/api/v1/reports/summary?hours=" + hours).with(reader("ADMIN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void preservesTraceOnUnavailableService() throws Exception {
        when(service.summary(24)).thenThrow(new DownstreamServiceException("El servicio de reportería no está disponible"));
        mvc.perform(get("/api/v1/reports/summary").with(reader("ADMIN")).header("X-Trace-Id", "report-failure"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.traceId").value("report-failure"))
                .andExpect(header().string("X-Trace-Id", "report-failure"));
    }

    @Test
    void administratorCanReadRealTimeActivity() throws Exception {
        when(service.activity(60)).thenReturn(new ActivityResponse(Instant.parse("2026-11-10T11:00:00Z"),
                Instant.parse("2026-11-10T12:00:00Z"), 1L, Map.of("OrderCreated", 1L),
                List.of(new ActivityResponse.ActorActivity("operador", 1L)),
                List.of(new ActivityResponse.MinuteActivity(Instant.parse("2026-11-10T11:30:00Z"), 1L)), null));
        when(service.activity(15)).thenReturn(new ActivityResponse(Instant.parse("2026-11-10T11:45:00Z"),
                Instant.parse("2026-11-10T12:00:00Z"), 0L, Map.of(), List.of(), List.of(), null));
        mvc.perform(get("/api/v1/reports/activity").with(reader("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(1))
                .andExpect(jsonPath("$.topActors[0].actorId").value("operador"));
        mvc.perform(get("/api/v1/reports/activity?minutes=15").with(reader("ADMIN"))).andExpect(status().isOk());
        verify(service).activity(60);
        verify(service).activity(15);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1441", "abc"})
    void validatesTheActivityWindow(String minutes) throws Exception {
        mvc.perform(get("/api/v1/reports/activity?minutes=" + minutes).with(reader("ADMIN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "OPERADOR", "AUDITOR"})
    void restrictsActivityToAdministrators(String role) throws Exception {
        mvc.perform(get("/api/v1/reports/activity").with(reader(role))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
