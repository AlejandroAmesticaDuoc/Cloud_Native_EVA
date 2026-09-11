package cl.duoc.pedidos360.bff.service;

import java.util.List;

import cl.duoc.pedidos360.bff.client.OrdersClient;
import cl.duoc.pedidos360.bff.dto.order.CreateOrderCommand;
import cl.duoc.pedidos360.bff.dto.order.CreateOrderRequest;
import cl.duoc.pedidos360.bff.dto.order.OrderResponse;
import cl.duoc.pedidos360.bff.dto.order.UpdateOrderStatusRequest;
import cl.duoc.pedidos360.bff.exception.ForbiddenOperationException;
import cl.duoc.pedidos360.bff.security.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class OrdersService {

    private static final String FORBIDDEN_MESSAGE =
            "El usuario no tiene permisos para acceder a este pedido";

    private final OrdersClient ordersClient;

    public OrdersService(OrdersClient ordersClient) {
        this.ordersClient = ordersClient;
    }

    public List<OrderResponse> listOrders(
            CurrentUser currentUser) {

        if (currentUser.canManageAllOrders()) {
            return ordersClient.listOrders();
        }

        return ordersClient
                .listOrdersByCustomerId(
                        currentUser.userId()
                )
                .stream()
                .filter(order ->
                        currentUser.userId().equals(
                                order.customerId()
                        ))
                .toList();
    }

    public OrderResponse getOrder(
            long id,
            CurrentUser currentUser) {

        OrderResponse order =
                ordersClient.getOrder(id);

        verifyOwnership(
                order,
                currentUser
        );

        return order;
    }

    public OrderResponse createOrder(
            CreateOrderRequest request,
            CurrentUser currentUser) {

        CreateOrderCommand command =
                new CreateOrderCommand(
                        currentUser.userId(),
                        request.items()
                );

        return ordersClient.createOrder(command);
    }

    public OrderResponse updateStatus(
            long id,
            UpdateOrderStatusRequest request) {

        return ordersClient.updateStatus(
                id,
                request
        );
    }

    public void cancelOrder(
            long id,
            CurrentUser currentUser) {

        if (!currentUser.canManageAllOrders()) {
            OrderResponse order =
                    ordersClient.getOrder(id);

            verifyOwnership(
                    order,
                    currentUser
            );
        }

        ordersClient.cancelOrder(id);
    }

    private void verifyOwnership(
            OrderResponse order,
            CurrentUser currentUser) {

        if (currentUser.canManageAllOrders()) {
            return;
        }

        if (order == null
                || !currentUser.userId().equals(
                        order.customerId()
                )) {

            throw new ForbiddenOperationException(
                    FORBIDDEN_MESSAGE
            );
        }
    }
}