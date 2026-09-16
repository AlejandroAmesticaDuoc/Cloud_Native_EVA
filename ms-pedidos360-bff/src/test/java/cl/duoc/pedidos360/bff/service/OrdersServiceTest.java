package cl.duoc.pedidos360.bff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import cl.duoc.pedidos360.bff.client.OrdersClient;
import cl.duoc.pedidos360.bff.dto.order.CreateOrderCommand;
import cl.duoc.pedidos360.bff.dto.order.CreateOrderItemRequest;
import cl.duoc.pedidos360.bff.dto.order.CreateOrderRequest;
import cl.duoc.pedidos360.bff.dto.order.OrderItemResponse;
import cl.duoc.pedidos360.bff.dto.order.OrderResponse;
import cl.duoc.pedidos360.bff.dto.order.OrderStatus;
import cl.duoc.pedidos360.bff.dto.order.UpdateOrderStatusRequest;
import cl.duoc.pedidos360.bff.exception.ForbiddenOperationException;
import cl.duoc.pedidos360.bff.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrdersServiceTest {

    private static final CurrentUser CLIENT_USER =
            new CurrentUser(
                    "cliente-real",
                    Set.of("CLIENTE")
            );

    private static final CurrentUser PRIVILEGED_USER =
            new CurrentUser(
                    "administrador",
                    Set.of("ADMIN", "CLIENTE")
            );

    @Mock
    private OrdersClient ordersClient;

    @InjectMocks
    private OrdersService ordersService;

    @Test
    void shouldListOnlyCustomerOrders() {
        when(ordersClient.listOrdersByCustomerId(
                "cliente-real"
        )).thenReturn(List.of(
                orderOwnedBy("cliente-real"),
                orderOwnedBy("otro-cliente")
        ));

        List<OrderResponse> result =
                ordersService.listOrders(CLIENT_USER);

        assertEquals(1, result.size());
        assertEquals(
                "cliente-real",
                result.getFirst().customerId()
        );

        verify(ordersClient)
                .listOrdersByCustomerId(
                        "cliente-real"
                );

        verify(ordersClient, never())
                .listOrders();
    }

    @Test
    void shouldAllowPrivilegedUserToListAllOrders() {
        when(ordersClient.listOrders())
                .thenReturn(List.of(
                        orderOwnedBy("otro-cliente")
                ));

        List<OrderResponse> result =
                ordersService.listOrders(
                        PRIVILEGED_USER
                );

        assertEquals(1, result.size());

        verify(ordersClient).listOrders();

        verify(ordersClient, never())
                .listOrdersByCustomerId(
                        "administrador"
                );
    }

    @Test
    void shouldAllowCustomerToReadOwnOrder() {
        when(ordersClient.getOrder(1001L))
                .thenReturn(orderOwnedBy(
                        "cliente-real"
                ));

        OrderResponse result =
                ordersService.getOrder(
                        1001L,
                        CLIENT_USER
                );

        assertEquals(1001L, result.id());
    }

    @Test
    void shouldRejectAnotherCustomersOrder() {
        when(ordersClient.getOrder(1001L))
                .thenReturn(orderOwnedBy(
                        "otro-cliente"
                ));

        assertThrows(
                ForbiddenOperationException.class,
                () -> ordersService.getOrder(
                        1001L,
                        CLIENT_USER
                )
        );
    }

    @Test
    void shouldCreateOrderUsingAuthenticatedUserId() {
        CreateOrderRequest request =
                new CreateOrderRequest(List.of(
                        new CreateOrderItemRequest(
                                10L,
                                2
                        )
                ));

        when(ordersClient.createOrder(
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(orderOwnedBy(
                "cliente-real"
        ));

        ordersService.createOrder(
                request,
                CLIENT_USER
        );

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

        assertEquals(
                request.items(),
                captor.getValue().items()
        );
    }

    @Test
    void shouldAllowCustomerToCancelOwnOrder() {
        when(ordersClient.getOrder(1001L))
                .thenReturn(orderOwnedBy(
                        "cliente-real"
                ));

        ordersService.cancelOrder(
                1001L,
                CLIENT_USER
        );

        verify(ordersClient).getOrder(1001L);
        verify(ordersClient).cancelOrder(1001L);
    }

    @Test
    void shouldNotCancelAnotherCustomersOrder() {
        when(ordersClient.getOrder(1001L))
                .thenReturn(orderOwnedBy(
                        "otro-cliente"
                ));

        assertThrows(
                ForbiddenOperationException.class,
                () -> ordersService.cancelOrder(
                        1001L,
                        CLIENT_USER
                )
        );

        verify(ordersClient).getOrder(1001L);

        verify(ordersClient, never())
                .cancelOrder(anyLong());
    }

    @Test
    void shouldDelegateStatusUpdate() {
        UpdateOrderStatusRequest request =
                new UpdateOrderStatusRequest(
                        OrderStatus.ACEPTADO
                );

        when(ordersClient.updateStatus(
                1001L,
                request
        )).thenReturn(orderOwnedBy(
                "cliente-real"
        ));

        ordersService.updateStatus(
                1001L,
                request
        );

        verify(ordersClient).updateStatus(
                1001L,
                request
        );
    }

    private OrderResponse orderOwnedBy(
            String customerId) {

        return new OrderResponse(
                1001L,
                customerId,
                OrderStatus.CREADO,
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