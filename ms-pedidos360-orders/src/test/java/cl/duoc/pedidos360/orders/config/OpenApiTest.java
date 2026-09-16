package cl.duoc.pedidos360.orders.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "API_DOCS_ENABLED=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTest {
    @Autowired MockMvc mvc;

    @Test
    void documentsCreationAndNoContentCancellation() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{id}/cancel'].post.responses['204'].content").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"));
    }

    @Test
    void documentationDoesNotDisableAuthentication() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
    }
}
