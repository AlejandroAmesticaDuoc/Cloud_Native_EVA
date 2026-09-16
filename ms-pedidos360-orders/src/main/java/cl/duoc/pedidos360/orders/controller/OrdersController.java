package cl.duoc.pedidos360.orders.controller;

import java.util.List;
import cl.duoc.pedidos360.orders.config.TraceIdFilter;
import cl.duoc.pedidos360.orders.dto.*;
import cl.duoc.pedidos360.orders.security.CurrentUser;
import cl.duoc.pedidos360.orders.service.OrdersService;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrdersController {
    private final OrdersService service;

    public OrdersController(OrdersService service) { this.service = service; }

    @GetMapping
    public List<OrderResponse> list(@RequestParam(required = false) @Size(max = 200) String customerId,
            JwtAuthenticationToken authentication) {
        return service.list(CurrentUser.from(authentication), customerId);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable @Positive long id, JwtAuthenticationToken authentication) {
        return service.get(id, CurrentUser.from(authentication));
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Pedido creado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class)))
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest body,
            JwtAuthenticationToken authentication, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body, CurrentUser.from(authentication),
                authentication.getToken().getTokenValue(), TraceIdFilter.resolve(request)));
    }

    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@PathVariable @Positive long id, @Valid @RequestBody UpdateOrderStatusRequest body,
            JwtAuthenticationToken authentication, HttpServletRequest request) {
        return service.changeStatus(id, body.status(), CurrentUser.from(authentication), TraceIdFilter.resolve(request));
    }

    @PostMapping("/{id}/cancel")
    @ApiResponse(responseCode = "204", description = "Pedido cancelado", content = @Content)
    public ResponseEntity<Void> cancel(@PathVariable @Positive long id, JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        service.cancel(id, CurrentUser.from(authentication), TraceIdFilter.resolve(request));
        return ResponseEntity.noContent().build();
    }
}
