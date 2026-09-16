package cl.duoc.pedidos360.bff.config;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogBoundaryTest {
    @Autowired private MockMvc mvc;

    @Test
    void shouldReturn400ForNonNumericProductIdentifier() throws Exception {
        mvc.perform(get("/api/v1/catalog/texto").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_pedidos360.access"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void shouldReplaceUnsafeTraceInUnauthorizedResponse() throws Exception {
        mvc.perform(get("/api/v1/catalog").header("X-Trace-Id", "x".repeat(101)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Trace-Id", matchesPattern("[A-Za-z0-9._-]{1,100}")))
                .andExpect(jsonPath("$.traceId", matchesPattern("[A-Za-z0-9._-]{1,100}")));
    }

    @Test
    void shouldReturnTheSameTraceHeaderOnSuccessfulRequests() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Trace-Id", "bff-catalog-001"))
                .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", "bff-catalog-001"));
    }
}
