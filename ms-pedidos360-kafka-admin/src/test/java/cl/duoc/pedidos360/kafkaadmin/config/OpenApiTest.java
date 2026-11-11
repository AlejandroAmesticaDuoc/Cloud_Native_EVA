package cl.duoc.pedidos360.kafkaadmin.config;

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
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/cluster'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/topics'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/topics'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/topics/{name}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/topics/{name}/configs'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/topics/{name}/partitions'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/consumer-groups/{groupId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/consumer-groups/{groupId}/offsets/reset'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kafka-admin/consumer-groups/{groupId}'].delete").exists())
                .andExpect(jsonPath("$.components.schemas.CreateTopicRequest.required").isArray());
    }

    @Test
    void keepsTheApiProtectedWhenDocumentationIsEnabled() throws Exception {
        mvc.perform(get("/api/v1/kafka-admin/topics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
