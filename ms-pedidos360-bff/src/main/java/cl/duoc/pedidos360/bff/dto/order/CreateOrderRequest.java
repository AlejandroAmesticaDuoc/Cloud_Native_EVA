package cl.duoc.pedidos360.bff.dto.order;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(

        @NotEmpty(message = "El pedido debe contener al menos un producto")
        @Size(
                max = 50,
                message = "El pedido no puede contener más de 50 productos"
        )
        @Valid
        List<CreateOrderItemRequest> items
) {
}