package cl.duoc.pedidos360.catalog.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cl.duoc.pedidos360.catalog.dto.KitchenTicketCommand;
import cl.duoc.pedidos360.catalog.service.KitchenTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KitchenTicketApiTest {
    private static final String API = "/api/v1/catalog/kitchen-tickets/";
    @Autowired MockMvc mvc;
    @Autowired KitchenTicketService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM kitchen_tickets");
    }

    JwtRequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_" + role));
    }

    void ticket(long orderId) {
        service.register(new KitchenTicketCommand(1, UUID.randomUUID(), orderId, "cliente-demo",
                List.of(new KitchenTicketCommand.Item(7L, 2)), Instant.parse("2026-10-08T15:30:00Z"), "trace-demo-001"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(API + "42")).andExpect(status().isUnauthorized());
    }

    @Test
    void requiresTheScopeEvenForAdministrators() throws Exception {
        mvc.perform(get(API + "42").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "AUDITOR", "OTRO"})
    void rejectsOtherRoles(String role) throws Exception {
        ticket(42);
        mvc.perform(get(API + "42").with(role(role))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OPERADOR"})
    void returnsTheTicketToKitchenRoles(String role) throws Exception {
        ticket(42);
        mvc.perform(get(API + "42").with(role(role)).header("X-Trace-Id", "kitchen-http"))
                .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", "kitchen-http"))
                .andExpect(jsonPath("$.orderId").value(42)).andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.ticket").value(org.hamcrest.Matchers.containsString("2 x Producto #7")))
                .andExpect(jsonPath("$.eventId").doesNotExist());
    }

    @Test
    void returnsNotFoundWhenTheOrderHasNoTicket() throws Exception {
        mvc.perform(get(API + "999").with(role("ADMIN"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ticket de cocina no encontrado"));
    }

    @Test
    void validatesTheOrderIdAndDeniesWrites() throws Exception {
        mvc.perform(get(API + "0").with(role("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(post(API + "42").with(role("ADMIN"))).andExpect(status().isForbidden());
    }
}
