package cl.duoc.pedidos360.bff.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.eq;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import cl.duoc.pedidos360.bff.client.CatalogClient;
import cl.duoc.pedidos360.bff.dto.catalog.CreateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.ProductResponse;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductStockRequest;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import cl.duoc.pedidos360.bff.exception.ResourceConflictException;
import cl.duoc.pedidos360.bff.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;


@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerTest {

    private static final String CATALOG_PATH =
            "/api/v1/catalog";

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    private static final String VALID_CREATE_BODY = """
            {
              "name": "Teclado mecánico",
              "price": 45990.00,
              "stock": 20
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogClient catalogClient;

    @Test
    void shouldListProductsWithScopeOnly()
            throws Exception {

        when(catalogClient.listProducts())
                .thenReturn(List.of(testProduct()));

        mockMvc.perform(get(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].name")
                        .value("Teclado mecánico"));

        verify(catalogClient).listProducts();
    }

    @Test
    void shouldAllowAdminToCreateProduct()
            throws Exception {

        when(catalogClient.createProduct(
                any(CreateProductRequest.class)
        )).thenReturn(testProduct());

        mockMvc.perform(post(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name")
                        .value("Teclado mecánico"))
                .andExpect(jsonPath("$.stock").value(20))
                .andExpect(jsonPath("$.active").value(true));

        ArgumentCaptor<CreateProductRequest> captor =
                ArgumentCaptor.forClass(
                        CreateProductRequest.class
                );

        verify(catalogClient).createProduct(captor.capture());

        CreateProductRequest request = captor.getValue();

        assertEquals("Teclado mecánico", request.name());
        assertEquals(20, request.stock());
        assertEquals(
                0,
                new BigDecimal("45990.00")
                        .compareTo(request.price())
        );
    }

    @Test
    void shouldRejectNegativeStockBeforeCallingClient()
            throws Exception {

        mockMvc.perform(patch(CATALOG_PATH + "/10/stock")
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stock": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        "La solicitud contiene datos inválidos"
                ));

        verifyNoInteractions(catalogClient);
    }

    @Test
    void shouldRejectChangesForNonAdminRoles()
            throws Exception {

        for (String role : List.of(
                "OPERADOR",
                "CLIENTE",
                "AUDITOR"
        )) {
            mockMvc.perform(post(CATALOG_PATH)
                            .with(jwtWithScopeAndRoles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_BODY))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put(CATALOG_PATH + "/10")
                            .with(jwtWithScopeAndRoles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "Teclado mecánico RGB",
                                      "price": 49990.00
                                    }
                                    """))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch(CATALOG_PATH + "/10/stock")
                            .with(jwtWithScopeAndRoles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "stock": 15
                                    }
                                    """))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete(CATALOG_PATH + "/10")
                            .with(jwtWithScopeAndRoles(role)))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(catalogClient);
    }

  @Test
    void shouldGetProductById()
            throws Exception {

        when(catalogClient.getProduct(10L))
                .thenReturn(testProduct());

        mockMvc.perform(get(CATALOG_PATH + "/10")
                        .with(jwtWithScopeAndRoles("CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name")
                        .value("Teclado mecánico"))
                .andExpect(jsonPath("$.stock").value(20));

        verify(catalogClient).getProduct(10L);
    }

    @Test
    void shouldAllowAdminToUpdateProduct()
            throws Exception {

        ProductResponse updatedProduct =
                new ProductResponse(
                        10L,
                        "Teclado mecánico RGB",
                        new BigDecimal("49990.00"),
                        20,
                        true
                );

        when(catalogClient.updateProduct(
                eq(10L),
                any(UpdateProductRequest.class)
        )).thenReturn(updatedProduct);

        mockMvc.perform(put(CATALOG_PATH + "/10")
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Teclado mecánico RGB",
                                  "price": 49990.00
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name")
                        .value("Teclado mecánico RGB"));

        ArgumentCaptor<UpdateProductRequest> captor =
                ArgumentCaptor.forClass(
                        UpdateProductRequest.class
                );

        verify(catalogClient).updateProduct(
                eq(10L),
                captor.capture()
        );

        UpdateProductRequest request = captor.getValue();

        assertEquals(
                "Teclado mecánico RGB",
                request.name()
        );
        assertEquals(
                0,
                new BigDecimal("49990.00")
                        .compareTo(request.price())
        );
    }

    @Test
    void shouldAllowAdminToSetStockToZero()
            throws Exception {

        UpdateProductStockRequest request =
                new UpdateProductStockRequest(0);

        ProductResponse updatedProduct =
                new ProductResponse(
                        10L,
                        "Teclado mecánico",
                        new BigDecimal("45990.00"),
                        0,
                        true
                );

        when(catalogClient.updateStock(10L, request))
                .thenReturn(updatedProduct);

        mockMvc.perform(patch(CATALOG_PATH + "/10/stock")
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stock": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.stock").value(0));

        verify(catalogClient).updateStock(10L, request);
    }

    @Test
    void shouldAllowAdminToDeactivateProduct()
            throws Exception {

        mockMvc.perform(delete(CATALOG_PATH + "/10")
                        .with(jwtWithScopeAndRoles("ADMIN")))
                .andExpect(status().isNoContent());

        verify(catalogClient).deactivateProduct(10L);
    }    

    @Test
    void shouldRejectUnauthenticatedRequests()
            throws Exception {

        mockMvc.perform(get(CATALOG_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post(CATALOG_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(catalogClient);
    }

    @Test
    void shouldRejectAdminWithoutRequiredScope()
            throws Exception {

        RequestPostProcessor adminWithoutScope =
                jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                );

        mockMvc.perform(get(CATALOG_PATH)
                        .with(adminWithoutScope))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(CATALOG_PATH)
                        .with(adminWithoutScope)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(catalogClient);
    }

    @Test
    void shouldRejectNonPositiveProductIds()
            throws Exception {

        for (long id : List.of(0L, -1L)) {
            String productPath = CATALOG_PATH + "/" + id;

            mockMvc.perform(get(productPath)
                            .with(jwtWithScopeAndRoles("ADMIN")))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(put(productPath)
                            .with(jwtWithScopeAndRoles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "Teclado mecánico",
                                      "price": 45990.00
                                    }
                                    """))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(patch(productPath + "/stock")
                            .with(jwtWithScopeAndRoles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "stock": 15
                                    }
                                    """))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(delete(productPath)
                            .with(jwtWithScopeAndRoles("ADMIN")))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(catalogClient);
    }

    @Test
    void shouldRejectUnknownProductFields()
            throws Exception {

        mockMvc.perform(post(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Teclado mecánico",
                                  "price": 45990.00,
                                  "stock": 20,
                                  "active": false
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "La solicitud contiene datos inválidos"
                ));

        verifyNoInteractions(catalogClient);
    }

    @Test
    void shouldRejectInvalidProductData()
            throws Exception {

        mockMvc.perform(post(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "price": 0,
                                  "stock": 20
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(CATALOG_PATH + "/10")
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "price": 0
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(catalogClient);
    }

    @Test
    void shouldReturnNotFoundForMissingProduct()
            throws Exception {

        when(catalogClient.getProduct(9999L))
                .thenThrow(new ResourceNotFoundException(
                        "Producto no encontrado"
                ));

        mockMvc.perform(get(CATALOG_PATH + "/9999")
                        .with(jwtWithScopeAndRoles("CLIENTE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value("Producto no encontrado"))
                .andExpect(jsonPath("$.path")
                        .value(CATALOG_PATH + "/9999"));

        verify(catalogClient).getProduct(9999L);
    }

    @Test
    void shouldReturnConflictWhenStockUpdateFails()
            throws Exception {

        UpdateProductStockRequest request =
                new UpdateProductStockRequest(15);

        when(catalogClient.updateStock(10L, request))
                .thenThrow(new ResourceConflictException(
                        "Existe un conflicto al procesar el producto"
                ));

        mockMvc.perform(patch(CATALOG_PATH + "/10/stock")
                        .with(jwtWithScopeAndRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stock": 15
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Existe un conflicto al procesar el producto"
                ));

        verify(catalogClient).updateStock(10L, request);
    }

    @Test
    void shouldReturnBadGatewayWhenCatalogIsUnavailable()
            throws Exception {

        when(catalogClient.listProducts())
                .thenThrow(new DownstreamServiceException(
                        "El servicio de catálogo no está disponible"
                ));

        mockMvc.perform(get(CATALOG_PATH)
                        .with(jwtWithScopeAndRoles("CLIENTE")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value(
                        "El servicio de catálogo no está disponible"
                ));

        verify(catalogClient).listProducts();
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

    private ProductResponse testProduct() {
        return new ProductResponse(
                10L,
                "Teclado mecánico",
                new BigDecimal("45990.00"),
                20,
                true
        );
    }
}