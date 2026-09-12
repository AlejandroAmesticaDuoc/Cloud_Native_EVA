package cl.duoc.pedidos360.bff.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;

import cl.duoc.pedidos360.bff.dto.order.CreateOrderCommand;
import cl.duoc.pedidos360.bff.dto.order.CreateOrderItemRequest;
import cl.duoc.pedidos360.bff.dto.order.OrderResponse;
import cl.duoc.pedidos360.bff.dto.order.OrderStatus;
import cl.duoc.pedidos360.bff.dto.order.UpdateOrderStatusRequest;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import cl.duoc.pedidos360.bff.exception.ResourceConflictException;
import cl.duoc.pedidos360.bff.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OrdersClientTest {

    private static final String BASE_URL =
            "http://orders.test:8081";

    private static final String ORDERS_URL =
            BASE_URL + "/api/v1/orders";

    private static final String ORDER_JSON = """
            {
              "id": 1001,
              "customerId": "usuario-entra-id",
              "status": "CREADO",
              "createdAt": "2026-09-08T18:30:00Z",
              "items": [
                {
                  "productId": 10,
                  "quantity": 2,
                  "unitPrice": 4500
                }
              ],
              "total": 9000
            }
            """;

    private MockRestServiceServer server;
    private OrdersClient ordersClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient
                .builder()
                .baseUrl(BASE_URL);

        server = MockRestServiceServer
                .bindTo(builder)
                .build();

        ordersClient =
                new OrdersClient(builder.build());
    }

    @Test
    void shouldListAllOrders() {
        server.expect(requestTo(ORDERS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[" + ORDER_JSON + "]",
                        MediaType.APPLICATION_JSON
                ));

        List<OrderResponse> response =
                ordersClient.listOrders();

        assertEquals(1, response.size());
        assertEquals(
                1001L,
                response.getFirst().id()
        );

        server.verify();
    }

    @Test
    void shouldListOrdersByCustomerId() {
        server.expect(requestTo(
                        ORDERS_URL
                                + "?customerId=usuario-entra-id"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[" + ORDER_JSON + "]",
                        MediaType.APPLICATION_JSON
                ));

        List<OrderResponse> response =
                ordersClient.listOrdersByCustomerId(
                        "usuario-entra-id"
                );

        assertEquals(1, response.size());
        assertEquals(
                "usuario-entra-id",
                response.getFirst().customerId()
        );

        server.verify();
    }

    @Test
    void shouldGetOrderById() {
        server.expect(requestTo(
                        ORDERS_URL + "/1001"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        ORDER_JSON,
                        MediaType.APPLICATION_JSON
                ));

        OrderResponse response =
                ordersClient.getOrder(1001L);

        assertEquals(1001L, response.id());
        assertEquals(
                OrderStatus.CREADO,
                response.status()
        );

        server.verify();
    }

    @Test
    void shouldCreateOrder() {
        CreateOrderCommand command =
                validCreateCommand();

        server.expect(requestTo(ORDERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(content().json("""
                        {
                          "customerId": "usuario-entra-id",
                          "items": [
                            {
                              "productId": 10,
                              "quantity": 2
                            }
                          ]
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .body(ORDER_JSON)
                        .contentType(
                                MediaType.APPLICATION_JSON
                        ));

        OrderResponse response =
                ordersClient.createOrder(command);

        assertEquals(1001L, response.id());

        server.verify();
    }

    @Test
    void shouldUpdateOrderStatus() {
        UpdateOrderStatusRequest request =
                new UpdateOrderStatusRequest(
                        OrderStatus.ACEPTADO
                );

        server.expect(requestTo(
                        ORDERS_URL + "/1001/status"
                ))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json(
                        "{\"status\":\"ACEPTADO\"}"
                ))
                .andRespond(withSuccess(
                        ORDER_JSON.replace(
                                "\"CREADO\"",
                                "\"ACEPTADO\""
                        ),
                        MediaType.APPLICATION_JSON
                ));

        OrderResponse response =
                ordersClient.updateStatus(
                        1001L,
                        request
                );

        assertEquals(
                OrderStatus.ACEPTADO,
                response.status()
        );

        server.verify();
    }

    @Test
    void shouldCancelOrder() {
        server.expect(requestTo(
                        ORDERS_URL + "/1001/cancel"
                ))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        ordersClient.cancelOrder(1001L);

        server.verify();
    }

    @Test
    void shouldTranslateBadRequest() {
        server.expect(requestTo(ORDERS_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("detalle interno inválido")
                        .contentType(MediaType.TEXT_PLAIN));

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ordersClient.createOrder(
                                validCreateCommand()
                        )
                );

        assertEquals(
                "La solicitud de pedido no es válida",
                exception.getMessage()
        );

        assertFalse(
                exception.getMessage().contains(
                        "detalle interno"
                )
        );

        server.verify();
    }

    @Test
    void shouldTranslateNotFound() {
        server.expect(requestTo(
                        ORDERS_URL + "/9999"
                ))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("detalle interno del servicio")
                        .contentType(MediaType.TEXT_PLAIN));

        ResourceNotFoundException exception =
                assertThrows(
                        ResourceNotFoundException.class,
                        () -> ordersClient.getOrder(9999L)
                );

        assertEquals(
                "Pedido no encontrado",
                exception.getMessage()
        );

        assertFalse(
                exception.getMessage().contains(
                        "detalle interno"
                )
        );

        server.verify();
    }

    @Test
    void shouldTranslateConflict() {
        UpdateOrderStatusRequest request =
                new UpdateOrderStatusRequest(
                        OrderStatus.ENTREGADO
                );

        server.expect(requestTo(
                        ORDERS_URL + "/1001/status"
                ))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("transición interna rechazada")
                        .contentType(MediaType.TEXT_PLAIN));

        ResourceConflictException exception =
                assertThrows(
                        ResourceConflictException.class,
                        () -> ordersClient.updateStatus(
                                1001L,
                                request
                        )
                );

        assertEquals(
                "Existe un conflicto al procesar el pedido",
                exception.getMessage()
        );

        assertFalse(
                exception.getMessage().contains(
                        "transición interna"
                )
        );

        server.verify();
    }

    @Test
    void shouldTranslateServerError() {
        server.expect(requestTo(ORDERS_URL))
                .andRespond(withStatus(
                                HttpStatus.INTERNAL_SERVER_ERROR
                        )
                        .body("error de base de datos")
                        .contentType(MediaType.TEXT_PLAIN));

        DownstreamServiceException exception =
                assertThrows(
                        DownstreamServiceException.class,
                        ordersClient::listOrders
                );

        assertEquals(
                "El servicio de pedidos no está disponible",
                exception.getMessage()
        );

        assertFalse(
                exception.getMessage().contains(
                        "base de datos"
                )
        );

        server.verify();
    }

    @Test
    void shouldTranslateConnectionError() {
        server.expect(requestTo(ORDERS_URL))
                .andRespond(withException(
                        new IOException(
                                "connection refused: host interno"
                        )
                ));

        DownstreamServiceException exception =
                assertThrows(
                        DownstreamServiceException.class,
                        ordersClient::listOrders
                );

        assertEquals(
                "El servicio de pedidos no está disponible",
                exception.getMessage()
        );

        assertFalse(
                exception.getMessage().contains(
                        "host interno"
                )
        );

        server.verify();
    }

    @Test
    void shouldPreserveForbiddenOwnershipResponse() {
        server.expect(requestTo(ORDERS_URL + "/1001"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("detalle privado"));
        var exception = assertThrows(cl.duoc.pedidos360.bff.exception.ForbiddenOperationException.class,
                () -> ordersClient.getOrder(1001L));
        assertFalse(exception.getMessage().contains("detalle privado"));
        server.verify();
    }

    private CreateOrderCommand validCreateCommand() {
        return new CreateOrderCommand(
                "usuario-entra-id",
                List.of(
                        new CreateOrderItemRequest(
                                10L,
                                2
                        )
                )
        );
    }
}
