package cl.duoc.pedidos360.catalog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTest {
    @Autowired private MockMvc mvc;

    @Test
    void shouldDescribeCreatedAndNoContentResponses() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/catalog'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/catalog/{id}'].delete.responses['204'].content").doesNotExist())
                .andExpect(jsonPath("$.paths['/internal/v1/catalog/stock/deductions'].post.responses['204']").exists());
    }

    @Test
    void shouldKeepTheApiProtectedWhenDocumentationIsEnabled() throws Exception {
        mvc.perform(get("/api/v1/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
