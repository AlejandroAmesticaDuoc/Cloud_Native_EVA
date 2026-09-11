package cl.duoc.pedidos360.bff.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import cl.duoc.pedidos360.bff.client.OrdersClient;
import cl.duoc.pedidos360.bff.dto.order.CreateOrderCommand;
import cl.duoc.pedidos360.bff.dto.order.OrderItemResponse;
import cl.duoc.pedidos360.bff.dto.order.OrderResponse;
import cl.duoc.pedidos360.bff.dto.order.OrderStatus;
import cl.duoc.pedidos360.bff.dto.order.UpdateOrderStatusRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class OrdersControllerTest {

    private static final String ORDERS_PATH =
            "/api/v1/orders";

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    private static final String VALID_CREATE_BODY = """
            {
              "items": [
                {
                  "productId": 10,
                  "quantity": 2
                }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrdersClient ordersClient;

    @Test
    void shouldListOnlyAuthenticatedCustomersOrders()
            throws Exception {

        when(ordersClient.listOrdersByCustomerId(
                "cliente-real"
        )).thenReturn(List.of(
                orderOwnedBy("cliente-real")
        ));

        mockMvc.perform(get(ORDERS_PATH)
                        .with(clientJwt(
                                "cliente-real"
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[0].customerId"
                ).value("cliente-real"));

        verify(ordersClient)
                .listOrdersByCustomerId(
                        "cliente-real"
                );

        verify(ordersClient, never())
                .listOrders();
    }

    @Test
    void shouldAllowPrivilegedUserToListAllOrders()
            throws Exception {

        when(ordersClient.listOrders())
                .thenReturn(List.of(
                        orderOwnedBy("otro-cliente")
                ));

        mockMvc.perform(get(ORDERS_PATH)
                        .with(privilegedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[0].customerId"
                ).value("otro-cliente"));

        verify(ordersClient).listOrders();

        verify(ordersClient, never())
                .listOrdersByCustomerId(
                        anyString()
                );
    }

    @Test
    void shouldCreateOrderUsingObjectIdFromJwt()
            throws Exception {

        when(ordersClient.createOrder(
                any(CreateOrderCommand.class)
        )).thenReturn(orderOwnedBy(
                "cliente-real"
        ));

        mockMvc.perform(post(ORDERS_PATH)
                        .with(clientJwt(
                                "cliente-real"
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(1001))
                .andExpect(jsonPath("$.customerId")
                        .value("cliente-real"));

        ArgumentCaptor<CreateOrderCommand> captor =
                ArgumentCaptor.forClass(
                        CreateOrderCommand.class
                );

        verify(ordersClient)
                .createOrder(captor.capture());

        assertEquals(
                "cliente-real",
                captor.getValue().customerId()
        );
    }

    @Test
    void shouldUseSubjectWhenObjectIdIsMissing()
            throws Exception {

        when(ordersClient.createOrder(
                any(CreateOrderCommand.class)
        )).thenReturn(orderOwnedBy(
                "subject-cliente"
        ));

        mockMvc.perform(post(ORDERS_PATH)
                        .with(clientJwtWithoutOid(
                                "subject-cliente"
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateOrderCommand> captor =
                ArgumentCaptor.forClass(
                        CreateOrderCommand.class
                );

        verify(ordersClient)
                .createOrder(captor.capture());

        assertEquals(
                "subject-cliente",
                captor.getValue().customerId()
        );
    }

    @Test
    void shouldRejectAnotherCustomersOrder()
            throws Exception {

        when(ordersClient.getOrder(1001L))
                .thenReturn(orderOwnedBy(
                        "otro-cliente"
                ));

        mockMvc.perform(get(
                        ORDERS_PATH + "/1001"
                )
                        .with(clientJwt(
                                "cliente-real"
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status")
                        .value(403))
                .andExpect(jsonPath("$.error")
                        .value("Forbidden"))
                .andExpect(jsonPath("$.message")
                        .value(
                                "El usuario no tiene permisos para acceder a este pedido"
                        ));

        verify(ordersClient)
                .getOrder(1001L);
    }

    @Test
    void shouldAllowCustomerToCancelOwnOrder()
            throws Exception {

        when(ordersClient.getOrder(1001L))
                .thenReturn(orderOwnedBy(
                        "cliente-real"
                ));

        mockMvc.perform(post(
                        ORDERS_PATH + "/1001/cancel"
                )
                        .with(clientJwt(
                                "cliente-real"
                        )))
                .andExpect(status().isNoContent());

        verify(ordersClient).getOrder(1001L);
        verify(ordersClient).cancelOrder(1001L);
    }

    @Test
    void shouldNotCancelAnotherCustomersOrder()
            throws Exception {

        when(ordersClient.getOrder(1001L))
                .thenReturn(orderOwnedBy(
                        "otro-cliente"
                ));

        mockMvc.perform(post(
                        ORDERS_PATH + "/1001/cancel"
                )
                        .with(clientJwt(
                                "cliente-real"
                        )))
                .andExpect(status().isForbidden());

        verify(ordersClient).getOrder(1001L);

        verify(ordersClient, never())
                .cancelOrder(anyLong());
    }

    @Test
    void shouldAllowOperatorToUpdateStatus()
            throws Exception {

        when(ordersClient.updateStatus(
                eq(1001L),
                any(UpdateOrderStatusRequest.class)
        )).thenReturn(orderWithStatus(
                "cliente-real",
                OrderStatus.ACEPTADO
        ));

        mockMvc.perform(patch(
                        ORDERS_PATH + "/1001/status"
                )
                        .with(operatorJwt())
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "status": "ACEPTADO"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("ACEPTADO"));

        ArgumentCaptor<UpdateOrderStatusRequest> captor =
                ArgumentCaptor.forClass(
                        UpdateOrderStatusRequest.class
                );

        verify(ordersClient).updateStatus(
                eq(1001L),
                captor.capture()
        );

        assertEquals(
                OrderStatus.ACEPTADO,
                captor.getValue().status()
        );
    }

    @Test
    void shouldRejectInvalidCreateRequest()
            throws Exception {

        mockMvc.perform(post(ORDERS_PATH)
                        .with(clientJwt(
                                "cliente-real"
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "items": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.message")
                        .value(
                                "La solicitud contiene datos inválidos"
                        ));

        verifyNoInteractions(ordersClient);
    }

    @Test
    void shouldRejectCustomerIdSentInRequestBody()
            throws Exception {

        mockMvc.perform(post(ORDERS_PATH)
                        .with(clientJwt(
                                "cliente-real"
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "customerId": "otro-cliente",
                                  "items": [
                                    {
                                      "productId": 10,
                                      "quantity": 2
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.message")
                        .value(
                                "La solicitud contiene datos inválidos"
                        ));

        verifyNoInteractions(ordersClient);
    }

    private RequestPostProcessor clientJwt(
            String objectId) {

        return jwt()
                .jwt(token -> token
                        .subject("subject-cliente")
                        .claim("oid", objectId))
                .authorities(
                        new SimpleGrantedAuthority(
                                REQUIRED_SCOPE
                        ),
                        new SimpleGrantedAuthority(
                                "ROLE_CLIENTE"
                        )
                );
    }

    private RequestPostProcessor clientJwtWithoutOid(
            String subject) {

        return jwt()
                .jwt(token ->
                        token.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority(
                                REQUIRED_SCOPE
                        ),
                        new SimpleGrantedAuthority(
                                "ROLE_CLIENTE"
                        )
                );
    }

    private RequestPostProcessor privilegedJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("subject-admin")
                        .claim(
                                "oid",
                                "administrador"
                        ))
                .authorities(
                        new SimpleGrantedAuthority(
                                REQUIRED_SCOPE
                        ),
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        ),
                        new SimpleGrantedAuthority(
                                "ROLE_CLIENTE"
                        )
                );
    }

    private RequestPostProcessor operatorJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("subject-operador")
                        .claim(
                                "oid",
                                "operador"
                        ))
                .authorities(
                        new SimpleGrantedAuthority(
                                REQUIRED_SCOPE
                        ),
                        new SimpleGrantedAuthority(
                                "ROLE_OPERADOR"
                        )
                );
    }

    private OrderResponse orderOwnedBy(
            String customerId) {

        return orderWithStatus(
                customerId,
                OrderStatus.CREADO
        );
    }

    private OrderResponse orderWithStatus(
            String customerId,
            OrderStatus status) {

        return new OrderResponse(
                1001L,
                customerId,
                status,
                Instant.parse(
                        "2026-09-08T18:30:00Z"
                ),
                List.of(
                        new OrderItemResponse(
                                10L,
                                2,
                                BigDecimal.valueOf(4500)
                        )
                ),
                BigDecimal.valueOf(9000)
        );
    }
}