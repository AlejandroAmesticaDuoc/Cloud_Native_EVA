package cl.duoc.pedidos360.bff.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "API_DOCS_ENABLED=true")
@AutoConfigureMockMvc
class OpenApiConfigTest {

    private static final String API_DOCS_PATH =
            "/v3/api-docs";

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldPublishOpenApiWithBearerJwt() throws Exception {
        mockMvc.perform(get(API_DOCS_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.info.title")
                        .value("Pedidos360 BFF"))
                .andExpect(jsonPath("$.info.version")
                        .value("v1"))
                .andExpect(jsonPath(
                        "$.components.securitySchemes.bearerAuth.type"
                ).value("http"))
                .andExpect(jsonPath(
                        "$.components.securitySchemes.bearerAuth.scheme"
                ).value("bearer"))
                .andExpect(jsonPath(
                        "$.components.securitySchemes.bearerAuth.bearerFormat"
                ).value("JWT"))
                .andExpect(jsonPath(
                        "$.security[0].bearerAuth"
                ).isArray());
    }

    @Test
    void shouldDocumentImplementedPublicContracts()
            throws Exception {

        mockMvc.perform(get(API_DOCS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/auth/me'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].post"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders/{id}'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders/{id}/status'].patch"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders/{id}/cancel'].post"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog'].post"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog/{id}'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog/{id}'].put"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog/{id}'].delete"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog/{id}/stock'].patch"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/actuator/health']"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.components.schemas.CreateOrderRequest.properties.items"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.CreateOrderRequest.properties.customerId"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.components.schemas.CreateOrderCommand"
                ).doesNotExist());
    }

    @Test
    void shouldServeSwaggerUiWithoutToken() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.TEXT_HTML
                ))
                .andExpect(content().string(
                        containsString("Swagger UI")
                ));

        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url")
                        .value(API_DOCS_PATH))
                .andExpect(jsonPath("$.persistAuthorization")
                        .value(false));
    }

    @Test
    void shouldKeepJwtAndScopeRequiredWhenSwaggerIsEnabled()
            throws Exception {

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void shouldRejectCatalogWritesWithoutAdminRole()
            throws Exception {

        mockMvc.perform(post("/api/v1/catalog")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        REQUIRED_SCOPE
                                ),
                                new SimpleGrantedAuthority(
                                        "ROLE_CLIENTE"
                                )
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Teclado",
                                  "price": 19990,
                                  "stock": 5
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void shouldDocumentCreatedResponsesWithTheirSchemas()
            throws Exception {

        mockMvc.perform(get(API_DOCS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].post"
                                + ".responses['201']"
                                + ".content['application/json']"
                                + ".schema['$ref']"
                ).value("#/components/schemas/OrderResponse"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].post"
                                + ".responses['200']"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog'].post"
                                + ".responses['201']"
                                + ".content['application/json']"
                                + ".schema['$ref']"
                ).value("#/components/schemas/ProductResponse"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog'].post"
                                + ".responses['200']"
                ).doesNotExist());
    }

    @Test
    void shouldDocumentNoContentResponsesWithoutBody()
            throws Exception {

        mockMvc.perform(get(API_DOCS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders/{id}/cancel'].post"
                                + ".responses['204']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders/{id}/cancel'].post"
                                + ".responses['204'].content"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders/{id}/cancel'].post"
                                + ".responses['200']"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog/{id}'].delete"
                                + ".responses['204']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog/{id}'].delete"
                                + ".responses['204'].content"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/catalog/{id}'].delete"
                                + ".responses['200']"
                ).doesNotExist());
    }    
}