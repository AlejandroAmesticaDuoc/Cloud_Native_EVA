package cl.duoc.pedidos360.bff.dto.order;

import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(

        @NotNull(message = "El estado del pedido es obligatorio")
        OrderStatus status
) {
}