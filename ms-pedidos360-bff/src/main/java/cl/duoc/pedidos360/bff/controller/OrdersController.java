package cl.duoc.pedidos360.bff.controller;

import java.util.List;

import cl.duoc.pedidos360.bff.dto.order.CreateOrderRequest;
import cl.duoc.pedidos360.bff.dto.order.OrderResponse;
import cl.duoc.pedidos360.bff.dto.order.UpdateOrderStatusRequest;
import cl.duoc.pedidos360.bff.security.CurrentUser;
import cl.duoc.pedidos360.bff.security.CurrentUserProvider;
import cl.duoc.pedidos360.bff.service.OrdersService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrdersController {

    private final OrdersService ordersService;
    private final CurrentUserProvider currentUserProvider;

    public OrdersController(
            OrdersService ordersService,
            CurrentUserProvider currentUserProvider) {

        this.ordersService = ordersService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<OrderResponse> listOrders(
            JwtAuthenticationToken authentication) {

        return ordersService.listOrders(
                currentUser(authentication)
        );
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(
            @PathVariable
            @Positive(message =
                    "El identificador del pedido debe ser positivo")
            long id,
            JwtAuthenticationToken authentication) {

        return ordersService.getOrder(
                id,
                currentUser(authentication)
        );
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid
            @RequestBody
            CreateOrderRequest request,
            JwtAuthenticationToken authentication) {

        OrderResponse response =
                ordersService.createOrder(
                        request,
                        currentUser(authentication)
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(
            @PathVariable
            @Positive(message =
                    "El identificador del pedido debe ser positivo")
            long id,
            @Valid
            @RequestBody
            UpdateOrderStatusRequest request) {

        return ordersService.updateStatus(
                id,
                request
        );
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable
            @Positive(message =
                    "El identificador del pedido debe ser positivo")
            long id,
            JwtAuthenticationToken authentication) {

        ordersService.cancelOrder(
                id,
                currentUser(authentication)
        );

        return ResponseEntity
                .noContent()
                .build();
    }

    private CurrentUser currentUser(
            JwtAuthenticationToken authentication) {

        return currentUserProvider.from(
                authentication
        );
    }
}