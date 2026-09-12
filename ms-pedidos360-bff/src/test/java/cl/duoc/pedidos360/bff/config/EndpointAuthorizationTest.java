package cl.duoc.pedidos360.bff.config;


import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import cl.duoc.pedidos360.bff.service.OrdersService;
import cl.duoc.pedidos360.bff.service.CatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(EndpointAuthorizationTest.ContractTestController.class)
class EndpointAuthorizationTest {

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    private static final String ORDERS_PATH =
            "/api/v1/orders";

    private static final String CATALOG_PATH =
            "/api/v1/catalog";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrdersService ordersService;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private cl.duoc.pedidos360.bff.service.AuditService auditService;

    @Test
    void shouldApplyClienteOrderPermissions()
            throws Exception {

        mockMvc.perform(get(ORDERS_PATH)
                        .with(jwtWithScopeAndRoles(
                                "CLIENTE"
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(get(ORDERS_PATH + "/10")
                        .with(jwtWithScopeAndRoles(
                                "CLIENTE"
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(post(ORDERS_PATH)
                        .with(jwtWithScopeAndRoles(
                                "CLIENTE"
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(validCreateOrderBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(post(
                        ORDERS_PATH + "/10/cancel"
                )
                        .with(jwtWithScopeAndRoles(
                                "CLIENTE"
                        )))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch(
                        ORDERS_PATH + "/10/status"
                )
                        .with(jwtWithScopeAndRoles(
                                "CLIENTE"
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldApplyOperadorAndAuditorOrderPermissions()
            throws Exception {

        mockMvc.perform(post(ORDERS_PATH)
                        .with(jwtWithScopeAndRoles(
                                "OPERADOR"
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(validCreateOrderBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(patch(
                        ORDERS_PATH + "/10/status"
                )
                        .with(jwtWithScopeAndRoles(
                                "OPERADOR"
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(validStatusBody()))
                .andExpect(status().isOk());

        mockMvc.perform(get(ORDERS_PATH)
                        .with(jwtWithScopeAndRoles(
                                "AUDITOR"
                        )))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(ORDERS_PATH)
                        .with(jwtWithScopeAndRoles(
                                "ADMIN"
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowCatalogReadingWithScopeOnly()
            throws Exception {

        mockMvc.perform(get(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles()))
                .andExpect(status().isOk());

        mockMvc.perform(get(CATALOG_PATH + "/10")
                        .with(jwtWithScopeAndRoles()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRestrictCatalogChangesToAdmin()
            throws Exception {

        mockMvc.perform(post(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles("OPERADOR")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Teclado mecánico",
                                  "price": 45990.00,
                                  "stock": 20
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(put(CATALOG_PATH + "/10")
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Teclado mecánico RGB",
                                  "price": 49990.00
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch(CATALOG_PATH + "/10/stock")
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stock": 15
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete(CATALOG_PATH + "/10")
                        .with(jwtWithScopeAndRoles("ADMIN")))
                .andExpect(status().isNoContent());
    }   

    @Test
    void shouldRestrictReportsToAdmin()
            throws Exception {

        mockMvc.perform(get(
                        "/api/v1/reports/summary"
                )
                        .with(jwtWithScopeAndRoles(
                                "ADMIN"
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/v1/reports/lead-time"
                )
                        .with(jwtWithScopeAndRoles(
                                "ADMIN"
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/v1/reports/summary"
                )
                        .with(jwtWithScopeAndRoles(
                                "AUDITOR"
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowAuditForAdminAndAuditorOnly()
            throws Exception {

        mockMvc.perform(get("/api/v1/audit")
                        .with(jwtWithScopeAndRoles(
                                "ADMIN"
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit")
                        .with(jwtWithScopeAndRoles(
                                "AUDITOR"
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/v1/audit/orders/10"
                )
                        .with(jwtWithScopeAndRoles(
                                "AUDITOR"
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit")
                        .with(jwtWithScopeAndRoles(
                                "CLIENTE"
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRequireScopeEvenWithAllowedRole()
            throws Exception {

        mockMvc.perform(get(
                        "/api/v1/reports/summary"
                )
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyRoutesOutsideContract()
            throws Exception {

        mockMvc.perform(put(ORDERS_PATH + "/10")
                        .with(jwtWithScopeAndRoles(
                                "ADMIN"
                        )))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(
                        "/api/v1/no-definido"
                )
                        .with(jwtWithScopeAndRoles(
                                "ADMIN"
                        )))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor jwtWithScopeAndRoles(
            String... roles) {

        List<GrantedAuthority> authorities =
                new ArrayList<>();

        authorities.add(new SimpleGrantedAuthority(
                REQUIRED_SCOPE
        ));

        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority(
                    "ROLE_" + role
            ));
        }

        return jwt().authorities(authorities);
    }

    private String validCreateOrderBody() {
        return """
                {
                  "items": [
                    {
                      "productId": 10,
                      "quantity": 2
                    }
                  ]
                }
                """;
    }

    private String validStatusBody() {
        return """
                {
                  "status": "ACEPTADO"
                }
                """;
    }

    @RestController
    static class ContractTestController {

        @GetMapping({
                "/api/v1/reports/summary",
                "/api/v1/reports/lead-time"
        })
        String getReports() {
            return "ok";
        }

    }
}
