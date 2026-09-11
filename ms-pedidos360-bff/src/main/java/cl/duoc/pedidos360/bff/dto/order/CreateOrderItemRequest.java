package cl.duoc.pedidos360.bff.dto.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderItemRequest(

        @NotNull(message = "El identificador del producto es obligatorio")
        @Positive(message = "El identificador del producto debe ser positivo")
        Long productId,

        @NotNull(message = "La cantidad es obligatoria")
        @Positive(message = "La cantidad debe ser positiva")
        Integer quantity
) {
}