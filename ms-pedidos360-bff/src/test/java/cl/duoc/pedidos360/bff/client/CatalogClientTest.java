package cl.duoc.pedidos360.bff.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

import java.math.BigDecimal;
import java.util.List;
import java.io.IOException;

import cl.duoc.pedidos360.bff.dto.catalog.CreateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductStockRequest;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import cl.duoc.pedidos360.bff.exception.ResourceConflictException;
import cl.duoc.pedidos360.bff.exception.ResourceNotFoundException;

class CatalogClientTest {

    private static final String BASE_URL =
            "http://catalog.test:8082";

    private static final String CATALOG_URL =
            BASE_URL + "/api/v1/catalog";

    private static final String PRODUCT_JSON = """
            {
              "id": 10,
              "name": "Teclado mecánico",
              "price": 45990.00,
              "stock": 20,
              "active": true
            }
            """;

    private MockRestServiceServer server;
    private CatalogClient catalogClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient
                .builder()
                .baseUrl(BASE_URL);

        server = MockRestServiceServer
                .bindTo(builder)
                .build();

        catalogClient =
                new CatalogClient(builder.build());
    }

    @Test
    void shouldListProducts() {
        server.expect(requestTo(CATALOG_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[" + PRODUCT_JSON + "]",
                        MediaType.APPLICATION_JSON
                ));

        List<ProductResponse> response =
                catalogClient.listProducts();

        assertEquals(1, response.size());
        assertEquals(
                10L,
                response.getFirst().id()
        );

        server.verify();
    }

    @Test
    void shouldGetProductById() {
        server.expect(requestTo(
                        CATALOG_URL + "/10"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        PRODUCT_JSON,
                        MediaType.APPLICATION_JSON
                ));

        ProductResponse response =
                catalogClient.getProduct(10L);

        assertEquals(10L, response.id());
        assertEquals(
                "Teclado mecánico",
                response.name()
        );

        assertEquals(
                0,
                new BigDecimal("45990.00")
                        .compareTo(response.price())
        );

        server.verify();
    }

    @Test
    void shouldCreateProduct() {
        CreateProductRequest request =
                new CreateProductRequest(
                        "Teclado mecánico",
                        new BigDecimal("45990.00"),
                        20
                );

        server.expect(requestTo(CATALOG_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(content().json("""
                        {
                          "name": "Teclado mecánico",
                          "price": 45990.00,
                          "stock": 20
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .body(PRODUCT_JSON)
                        .contentType(
                                MediaType.APPLICATION_JSON
                        ));

        ProductResponse response =
                catalogClient.createProduct(request);

        assertEquals(10L, response.id());
        assertEquals(Boolean.TRUE, response.active());

        server.verify();
    }
    @Test
    void shouldUpdateProduct() {
        UpdateProductRequest request =
                new UpdateProductRequest(
                        "Teclado mecánico RGB",
                        new BigDecimal("49990.00")
                );

        String updatedProductJson = PRODUCT_JSON
                .replace(
                        "Teclado mecánico",
                        "Teclado mecánico RGB"
                )
                .replace(
                        "45990.00",
                        "49990.00"
                );

        server.expect(requestTo(CATALOG_URL + "/10"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentType(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(content().json("""
                        {
                          "name": "Teclado mecánico RGB",
                          "price": 49990.00
                        }
                        """))
                .andRespond(withSuccess(
                        updatedProductJson,
                        MediaType.APPLICATION_JSON
                ));

        ProductResponse response =
                catalogClient.updateProduct(10L, request);

        assertEquals(10L, response.id());
        assertEquals(
                "Teclado mecánico RGB",
                response.name()
        );
        assertEquals(
                0,
                new BigDecimal("49990.00")
                        .compareTo(response.price())
        );

        server.verify();
    }

    @Test
    void shouldUpdateProductStock() {
        UpdateProductStockRequest request =
                new UpdateProductStockRequest(15);

        String updatedProductJson = PRODUCT_JSON.replace(
                "\"stock\": 20",
                "\"stock\": 15"
        );

        server.expect(requestTo(
                        CATALOG_URL + "/10/stock"
                ))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().contentType(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(content().json("""
                        {
                          "stock": 15
                        }
                        """))
                .andRespond(withSuccess(
                        updatedProductJson,
                        MediaType.APPLICATION_JSON
                ));

        ProductResponse response =
                catalogClient.updateStock(10L, request);

        assertEquals(10L, response.id());
        assertEquals(15, response.stock());

        server.verify();
    }

    @Test
    void shouldDeactivateProduct() {
        server.expect(requestTo(CATALOG_URL + "/10"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        catalogClient.deactivateProduct(10L);

        server.verify();
    }  
    @Test
    void shouldTranslateBadRequest() {
        server.expect(requestTo(CATALOG_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("detalle interno inválido")
                        .contentType(MediaType.TEXT_PLAIN));

        CreateProductRequest request =
                new CreateProductRequest(
                        "Teclado mecánico",
                        new BigDecimal("45990.00"),
                        20
                );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> catalogClient.createProduct(request)
        );

        assertEquals(
                "La solicitud de catálogo no es válida",
                exception.getMessage()
        );

        server.verify();
    }

    @Test
    void shouldTranslateNotFound() {
        server.expect(requestTo(CATALOG_URL + "/9999"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("detalle interno del producto")
                        .contentType(MediaType.TEXT_PLAIN));

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> catalogClient.getProduct(9999L)
        );

        assertEquals(
                "Producto no encontrado",
                exception.getMessage()
        );

        server.verify();
    }

    @Test
    void shouldTranslateConflict() {
        server.expect(requestTo(CATALOG_URL + "/10/stock"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("conflicto interno de stock")
                        .contentType(MediaType.TEXT_PLAIN));

        UpdateProductStockRequest request =
                new UpdateProductStockRequest(15);

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> catalogClient.updateStock(10L, request)
        );

        assertEquals(
                "Existe un conflicto al procesar el producto",
                exception.getMessage()
        );

        server.verify();
    }

    @Test
    void shouldTranslateServerError() {
        server.expect(requestTo(CATALOG_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(
                                HttpStatus.INTERNAL_SERVER_ERROR
                        )
                        .body("error interno de base de datos")
                        .contentType(MediaType.TEXT_PLAIN));

        DownstreamServiceException exception = assertThrows(
                DownstreamServiceException.class,
                catalogClient::listProducts
        );

        assertEquals(
                "El servicio de catálogo no está disponible",
                exception.getMessage()
        );

        server.verify();
    }

    @Test
    void shouldTranslateConnectionError() {
        server.expect(requestTo(CATALOG_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withException(
                        new IOException(
                                "connection refused: host interno"
                        )
                ));

        DownstreamServiceException exception = assertThrows(
                DownstreamServiceException.class,
                catalogClient::listProducts
        );

        assertEquals(
                "El servicio de catálogo no está disponible",
                exception.getMessage()
        );

        server.verify();
    }

    @Test
    void shouldRejectEmptyResponseBody() {
        server.expect(requestTo(CATALOG_URL + "/10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());

        DownstreamServiceException exception = assertThrows(
                DownstreamServiceException.class,
                () -> catalogClient.getProduct(10L)
        );

        assertEquals(
                "El servicio de catálogo no está disponible",
                exception.getMessage()
        );

        server.verify();
    }      
}