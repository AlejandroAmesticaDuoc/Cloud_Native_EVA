package cl.duoc.pedidos360.orders.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
import cl.duoc.pedidos360.orders.client.CatalogClient;
import cl.duoc.pedidos360.orders.dto.*;
import cl.duoc.pedidos360.orders.entity.*;
import cl.duoc.pedidos360.orders.exception.*;
import cl.duoc.pedidos360.orders.repository.OrderRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

public abstract class OrdersApiContract {
    @Autowired protected MockMvc mvc;
    @Autowired protected OrderRepository repository;
    @Autowired protected JdbcTemplate jdbc;
    @MockitoBean protected CatalogClient catalog;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM purchase_orders");
    }

    @BeforeEach
    void catalogProducts() {
        when(catalog.product(anyLong(), anyString(), anyString())).thenAnswer(call ->
                new ProductResponse(call.getArgument(0), "Producto", new BigDecimal("1200.50"), 10, true));
    }

    protected RequestPostProcessor user(String id, String role) {
        return jwt().jwt(token -> token.subject(id).claim("oid", id)).authorities(
                new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_" + role));
    }

    protected PurchaseOrder order(String owner, OrderStatus state) {
        var order = new PurchaseOrder(owner, List.of(new OrderItem(10L, 2, new BigDecimal("1200.50"))));
        order.complete(state);
        return repository.saveAndFlush(order);
    }

    @Test
    void healthAndReadinessArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void requiresTokenAndScope() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"CLIENTE,200", "OPERADOR,200", "ADMIN,200", "AUDITOR,403", "OTRO,403"})
    void restrictsListByRole(String role, int status) throws Exception {
        mvc.perform(get("/api/v1/orders").with(user("ana", role))).andExpect(status().is(status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "AUDITOR"})
    void doesNotAllowTheseRolesToCreate(String role) throws Exception {
        mvc.perform(post("/api/v1/orders").with(user("ana", role)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"ana\",\"items\":[{\"productId\":10,\"quantity\":2}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsWithTrustedIdentityAndCatalogPrices() throws Exception {
        mvc.perform(post("/api/v1/orders").with(user("ana", "CLIENTE")).header("X-Trace-Id", "orders-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"ana\",\"items\":[{\"productId\":10,\"quantity\":2},{\"productId\":11,\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andExpect(header().string("X-Trace-Id", "orders-test"))
                .andExpect(jsonPath("$.customerId").value("ana")).andExpect(jsonPath("$.status").value("CREADO"))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].unitPrice").value(1200.50))
                .andExpect(jsonPath("$.total").value(3601.50)).andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.version").doesNotExist()).andExpect(jsonPath("$.pendingStatus").doesNotExist());
        assertEquals(1, repository.count());
        String payload = jdbc.queryForObject("SELECT payload FROM notification_outbox", String.class);
        assertNotNull(payload);
        assertTrue(payload.contains("\"status\":\"CREADO\""));
        assertTrue(payload.contains("\"traceId\":\"orders-test\""));
        assertFalse(payload.contains("Authorization"));
        assertFalse(payload.contains("Bearer"));
        verify(catalog).product(eq(10L), anyString(), eq("orders-test"));
        verify(catalog, never()).deduct(any(), anyString());
    }

    @Test
    void operatorCreatesForTheirOwnIdentity() throws Exception {
        mvc.perform(post("/api/v1/orders").with(user("operador", "OPERADOR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"operador\",\"items\":[{\"productId\":10,\"quantity\":2}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.customerId").value("operador"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"customerId\":\"ana\",\"items\":[]}", "{\"customerId\":\"ana\",\"items\":[null]}",
        "{\"customerId\":\"ana\",\"items\":[{\"productId\":0,\"quantity\":1}]}",
        "{\"customerId\":\"ana\",\"items\":[{\"productId\":1,\"quantity\":0}]}",
        "{\"customerId\":\"ana\",\"items\":[{\"productId\":1,\"quantity\":1.5}]}",
        "{\"customerId\":\"ana\",\"items\":[{\"productId\":1,\"quantity\":1,\"unitPrice\":1}]}",
        "{\"customerId\":\"ana\",\"total\":1,\"items\":[{\"productId\":1,\"quantity\":1}]}",
        "{\"customerId\":\"ana\",\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}",
        "{\"customerId\":\"\",\"items\":[{\"productId\":1,\"quantity\":1}]}"
    })
    void rejectsInvalidRequests(String body) throws Exception {
        mvc.perform(post("/api/v1/orders").with(user("ana", "CLIENTE")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.traceId").exists());
        assertEquals(0, repository.count());
    }

    @Test
    void rejectsTooManyItems() throws Exception {
        String items = java.util.stream.LongStream.rangeClosed(1, 51)
                .mapToObj(id -> "{\"productId\":" + id + ",\"quantity\":1}").collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/api/v1/orders").with(user("ana", "CLIENTE")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"ana\",\"items\":[" + items + "]}")).andExpect(status().isBadRequest());
    }

    @Test
    void preventsSpoofingForClientAndOperator() throws Exception {
        for (String role : List.of("CLIENTE", "OPERADOR")) {
            mvc.perform(post("/api/v1/orders").with(user("ana", role)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"customerId\":\"otro\",\"items\":[{\"productId\":10,\"quantity\":2}]}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void enforcesOwnershipEvenWithoutBff() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        order("otro", OrderStatus.CREADO);
        mvc.perform(get("/api/v1/orders").with(user("ana", "CLIENTE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/orders").param("customerId", "otro").with(user("ana", "CLIENTE")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/" + id).with(user("otro", "CLIENTE"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("otro", "CLIENTE")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders").with(user("admin", "ADMIN"))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/orders").param("customerId", "ana").with(user("op", "OPERADOR")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "texto", "999999999999999999999999"})
    void validatesPathIdentifiers(String id) throws Exception {
        mvc.perform(get("/api/v1/orders/" + id).with(user("ana", "CLIENTE"))).andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForMissingOrder() throws Exception {
        mvc.perform(get("/api/v1/orders/99999").with(user("ana", "CLIENTE"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/99999/cancel").with(user("ana", "CLIENTE"))).andExpect(status().isNotFound());
    }

    @Test
    void appliesTheCompleteStateSequence() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        for (String target : List.of("ACEPTADO", "EN_PREPARACION", "DESPACHADO", "ENTREGADO")) {
            for (int repeat = 0; repeat < 2; repeat++) {
                mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("op", "OPERADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(target));
            }
        }
        verify(catalog, times(1)).deduct(argThat(request -> request.orderId() == id
                && request.items().getFirst().quantity() == 2), anyString());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "CLIENTE")))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest
    @CsvSource({"CREADO,DESPACHADO", "CREADO,ENTREGADO", "CREADO,EN_PREPARACION",
        "ACEPTADO,CREADO", "EN_PREPARACION,CANCELADO", "EN_PREPARACION,ACEPTADO",
        "DESPACHADO,CANCELADO", "ENTREGADO,ACEPTADO", "CANCELADO,ACEPTADO"})
    void rejectsInvalidTransitions(OrderStatus initial, OrderStatus target) throws Exception {
        long id = order("ana", initial).getId();
        mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("op", "OPERADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"}"))
                .andExpect(status().isConflict());
        assertEquals(initial, repository.findById(id).orElseThrow().getStatus());
        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsUnknownAndNullStatuses() throws Exception {
        for (String body : List.of("{\"status\":\"PAGADO\"}", "{\"status\":null}", "{}")) {
            mvc.perform(patch("/api/v1/orders/1/status").with(user("op", "OPERADOR"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void clientCannotChangeStatusAndAuditorCannotCancel() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("ana", "CLIENTE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACEPTADO\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "AUDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelsCreatedOrderWithoutStockOperation() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "CLIENTE")))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        verifyNoInteractions(catalog);
        assertEquals(OrderStatus.CANCELADO, repository.findById(id).orElseThrow().getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class));
    }

    @Test
    void cancelsAcceptedOrderAndReleasesOnce() throws Exception {
        long id = order("ana", OrderStatus.ACEPTADO).getId();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "CLIENTE")))
                    .andExpect(status().isNoContent());
        }
        verify(catalog, times(1)).release(eq(id), anyString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class));
    }

    @Test
    void patchCancellationAlsoReleasesStock() throws Exception {
        long id = order("ana", OrderStatus.ACEPTADO).getId();
        mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("op", "OPERADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELADO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELADO"));
        verify(catalog).release(eq(id), anyString());
    }

    @Test
    void rollsBackCreationWhenAProductIsMissing() throws Exception {
        when(catalog.product(eq(11L), anyString(), anyString())).thenThrow(new OrderNotFoundException());
        mvc.perform(post("/api/v1/orders").with(user("ana", "CLIENTE")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"ana\",\"items\":[{\"productId\":10,\"quantity\":2},{\"productId\":11,\"quantity\":1}]}"))
                .andExpect(status().isNotFound());
        assertEquals(0, repository.count());
    }

    @Test
    void mapsUnavailableCatalogWithoutLeakingDetails() throws Exception {
        when(catalog.product(anyLong(), anyString(), anyString())).thenThrow(new CatalogUnavailableException());
        mvc.perform(post("/api/v1/orders").with(user("ana", "CLIENTE")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"ana\",\"items\":[{\"productId\":10,\"quantity\":2}]}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.traceId").exists());
        assertEquals(0, repository.count());
    }

    @Test
    void stockRejectionKeepsCreatedStateAndAllowsCancellation() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        doThrow(new StockRejectedException()).when(catalog).deduct(any(), anyString());
        mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("op", "OPERADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACEPTADO\"}")).andExpect(status().isConflict());
        var stored = repository.findById(id).orElseThrow();
        assertEquals(OrderStatus.CREADO, stored.getStatus());
        assertNull(stored.getPendingStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class));
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "CLIENTE"))).andExpect(status().isNoContent());
    }

    @Test
    void preservesAcceptanceIntentOnTimeoutAndResumesSameAction() throws Exception {
        long id = order("ana", OrderStatus.CREADO).getId();
        doThrow(new CatalogUnavailableException()).doNothing().when(catalog).deduct(any(), anyString());
        mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("op", "OPERADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACEPTADO\"}")).andExpect(status().isBadGateway());
        var stored = repository.findById(id).orElseThrow();
        assertEquals(OrderStatus.CREADO, stored.getStatus());
        assertEquals(OrderStatus.ACEPTADO, stored.getPendingStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class));
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "CLIENTE"))).andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("op", "OPERADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACEPTADO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACEPTADO"));
        assertNull(repository.findById(id).orElseThrow().getPendingStatus());
    }

    @Test
    void preservesReleaseIntentOnTimeout() throws Exception {
        long id = order("ana", OrderStatus.ACEPTADO).getId();
        doThrow(new CatalogUnavailableException()).doNothing().when(catalog).release(eq(id), anyString());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "CLIENTE"))).andExpect(status().isBadGateway());
        assertEquals(OrderStatus.CANCELADO, repository.findById(id).orElseThrow().getPendingStatus());
        mvc.perform(patch("/api/v1/orders/" + id + "/status").with(user("op", "OPERADOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EN_PREPARACION\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("ana", "CLIENTE"))).andExpect(status().isNoContent());
        assertEquals(OrderStatus.CANCELADO, repository.findById(id).orElseThrow().getStatus());
    }

    @Test
    void rejectsUnsupportedContentAndSanitizesTrace() throws Exception {
        mvc.perform(post("/api/v1/orders").with(user("ana", "CLIENTE")).contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType());
        var response = mvc.perform(get("/api/v1/orders").header("X-Trace-Id", "x".repeat(101)))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertNotEquals("x".repeat(101), response.getHeader("X-Trace-Id"));
    }
}
