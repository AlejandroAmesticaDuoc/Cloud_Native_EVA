package cl.duoc.pedidos360.catalog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateProductStockRequest(

        @NotNull(message = "El stock del producto es obligatorio")
        @PositiveOrZero(message = "El stock del producto no puede ser negativo")
        Integer stock
) {
}