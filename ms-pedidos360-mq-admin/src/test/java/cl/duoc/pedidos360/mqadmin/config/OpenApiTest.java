package cl.duoc.pedidos360.mqadmin.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired
    private MockMvc mvc;

    @Test
    void documentsEveryAdministrationEndpoint() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/queues'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/queues'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/queues/{name}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/queues/{name}/messages'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/exchanges'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/exchanges/{name}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/bindings'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/dead-letter-queues'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/mq-admin/dead-letter-queues/{name}/replay'].post").exists())
                .andExpect(jsonPath("$.components.schemas.CreateQueueRequest.required[0]").value("name"));
    }

    @Test
    void keepsTheApiProtectedWhenDocumentationIsEnabled() throws Exception {
        mvc.perform(get("/api/v1/mq-admin/queues")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
