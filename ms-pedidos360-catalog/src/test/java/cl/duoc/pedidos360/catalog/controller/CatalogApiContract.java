package cl.duoc.pedidos360.catalog.controller;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.repository.ProductRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// Sin transacción de test: cada llamada HTTP debe confirmar o revertir su propia operación.
public abstract class CatalogApiContract {
    @Autowired protected MockMvc mvc;
    @Autowired protected ProductRepository products;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;
    private static final String API = "/api/v1/catalog";
    private static final String STOCK = "/internal/v1/catalog/stock/deductions";

    protected JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    protected JwtRequestPostProcessor reader() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"));
    }

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM stock_deduction_items");
        jdbc.update("DELETE FROM stock_deductions");
        jdbc.update("DELETE FROM products");
    }

    @Test
    void shouldCompleteCrudWithTheBffContract() throws Exception {
        mvc.perform(get(API).with(reader())).andExpect(status().isOk()).andExpect(content().json("[]"));
        String created = mvc.perform(post(API).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Café\",\"price\":2500.50,\"stock\":20}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").doesNotExist()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();
        mvc.perform(get(API + "/" + id).with(reader())).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Café")).andExpect(jsonPath("$.stock").value(20));
        mvc.perform(put(API + "/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Café grande\",\"price\":3000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(20));
        mvc.perform(patch(API + "/" + id + "/stock").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(0));
        mvc.perform(delete(API + "/" + id).with(admin())).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(delete(API + "/" + id).with(admin())).andExpect(status().isNoContent());
        mvc.perform(get(API + "/" + id).with(reader())).andExpect(status().isNotFound());
        mvc.perform(get(API).with(reader())).andExpect(content().json("[]"));
        assertFalse(products.findById(id).orElseThrow().isActive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\"\",\"price\":1,\"stock\":0}",
            "{\"name\":\"Café\",\"price\":0,\"stock\":0}",
            "{\"name\":\"Café\",\"price\":1.123,\"stock\":0}",
            "{\"name\":\"Café\",\"price\":1,\"stock\":-1}",
            "{\"name\":\"Café\",\"price\":1,\"stock\":0,\"active\":true}",
            "{\"name\":", "null"})
    void shouldRejectInvalidCreateWithoutPersisting(String body) throws Exception {
        mvc.perform(post(API).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertEquals(0, products.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "texto", "999999999999999999999"})
    void shouldRejectInvalidIdentifiers(String id) throws Exception {
        mvc.perform(get(API + "/" + id).with(reader())).andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFoundForMissingProductAndPreventUpdatingInactiveProduct() throws Exception {
        mvc.perform(get(API + "/999999").with(reader())).andExpect(status().isNotFound());
        Product product = product(5);
        product.deactivate();
        products.saveAndFlush(product);
        mvc.perform(put(API + "/" + product.getId()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Otro\",\"price\":1}")).andExpect(status().isNotFound());
        mvc.perform(patch(API + "/" + product.getId() + "/stock").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"stock\":9}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRequireTokenAndScopeAndAdminForWrites() throws Exception {
        mvc.perform(get(API)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        mvc.perform(get(API).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(API).with(reader()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Café\",\"price\":1,\"stock\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/ruta-no-acordada").with(admin())).andExpect(status().isForbidden());
        mvc.perform(get("/v3/api-docs").with(admin())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "OPERADOR", "AUDITOR"})
    void shouldRejectEveryCatalogMutationFromNonAdministrators(String role) throws Exception {
        var user = jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_" + role));
        mvc.perform(post(API).with(user)).andExpect(status().isForbidden());
        mvc.perform(put(API + "/1").with(user)).andExpect(status().isForbidden());
        mvc.perform(patch(API + "/1/stock").with(user)).andExpect(status().isForbidden());
        mvc.perform(delete(API + "/1").with(user)).andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectInvalidStockAndUnknownUpdateFields() throws Exception {
        long id = product(5).getId();
        mvc.perform(patch(API + "/" + id + "/stock").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stock\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(put(API + "/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Otro\",\"price\":1,\"stock\":500}"))
                .andExpect(status().isBadRequest());
        assertEquals(5, products.findById(id).orElseThrow().getStock());
    }

    @Test
    void shouldReturnUnsupportedMediaType() throws Exception {
        mvc.perform(post(API).with(admin()).contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void shouldPreserveSafeTraceAndReplaceUnsafeTrace() throws Exception {
        mvc.perform(get(API).with(reader()).header("X-Trace-Id", "catalog-test-001"))
                .andExpect(header().string("X-Trace-Id", "catalog-test-001"));
        mvc.perform(get(API).header("X-Trace-Id", "x".repeat(101)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Trace-Id", matchesPattern("[A-Za-z0-9._-]{1,100}")))
                .andExpect(jsonPath("$.traceId", matchesPattern("[A-Za-z0-9._-]{1,100}")));
    }

    @Test
    void shouldDeductAndReleaseOnlyOnce() throws Exception {
        Product product = product(10);
        String request = deduction(1001, product.getId(), 3);
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNoContent());
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNoContent());
        assertEquals(7, products.findById(product.getId()).orElseThrow().getStock());
        mvc.perform(post(STOCK + "/1001/release").with(admin())).andExpect(status().isNoContent());
        mvc.perform(post(STOCK + "/1001/release").with(admin())).andExpect(status().isNoContent());
        assertEquals(10, products.findById(product.getId()).orElseThrow().getStock());
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldRollbackTheEntireBatchIfOneProductHasInsufficientStock() throws Exception {
        Product first = product(10);
        Product second = product(1);
        String request = "{\"orderId\":1002,\"items\":[{\"productId\":%d,\"quantity\":3},{\"productId\":%d,\"quantity\":2}]}"
                .formatted(first.getId(), second.getId());
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
        assertEquals(10, products.findById(first.getId()).orElseThrow().getStock());
        assertEquals(1, products.findById(second.getId()).orElseThrow().getStock());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_deductions", Integer.class));
    }

    @Test
    void shouldRejectChangedPayloadForTheSameOrder() throws Exception {
        long id = product(10).getId();
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON).content(deduction(1003, id, 1)))
                .andExpect(status().isNoContent());
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON).content(deduction(1003, id, 2)))
                .andExpect(status().isConflict());
        assertEquals(9, products.findById(id).orElseThrow().getStock());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"orderId\":1,\"items\":[]}", "{\"orderId\":0,\"items\":[]}",
            "{\"orderId\":1,\"items\":[null]}",
            "{\"orderId\":1,\"items\":[{\"productId\":1,\"quantity\":0}]}",
            "{\"orderId\":1,\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}"})
    void shouldRejectMalformedOrDuplicateStockItems(String body) throws Exception {
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldNotLetAReaderDeductOrReleaseStock() throws Exception {
        mvc.perform(post(STOCK).with(reader())).andExpect(status().isForbidden());
        mvc.perform(post(STOCK + "/1/release").with(reader())).andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowOperatorToDeductStock() throws Exception {
        var operator = jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_OPERADOR"));
        mvc.perform(post(STOCK).with(operator).contentType(MediaType.APPLICATION_JSON)
                .content(deduction(1004, product(5).getId(), 1))).andExpect(status().isNoContent());
    }

    @Test
    void shouldNotDeductInactiveProductsButAllowReturningTheirPreviousStock() throws Exception {
        Product product = product(10);
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(deduction(1005, product.getId(), 1))).andExpect(status().isNoContent());
        mvc.perform(delete(API + "/" + product.getId()).with(admin())).andExpect(status().isNoContent());
        mvc.perform(post(STOCK).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(deduction(1006, product.getId(), 1))).andExpect(status().isNotFound());
        mvc.perform(post(STOCK + "/1005/release").with(admin())).andExpect(status().isNoContent());
        Product stored = products.findById(product.getId()).orElseThrow();
        assertFalse(stored.isActive());
        assertEquals(10, stored.getStock());
    }

    protected Product product(int stock) {
        return products.saveAndFlush(new Product("Café", new BigDecimal("2500.00"), stock));
    }

    protected String deduction(long orderId, long productId, int quantity) {
        return "{\"orderId\":%d,\"items\":[{\"productId\":%d,\"quantity\":%d}]}"
                .formatted(orderId, productId, quantity);
    }
}
