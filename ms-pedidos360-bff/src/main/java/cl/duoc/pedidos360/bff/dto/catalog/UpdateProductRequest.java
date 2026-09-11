package cl.duoc.pedidos360.bff.dto.catalog;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProductRequest(

        @NotBlank(message = "El nombre del producto es obligatorio")
        @Size(
                max = 100,
                message = "El nombre del producto no puede superar los 100 caracteres"
        )
        String name,

        @NotNull(message = "El precio del producto es obligatorio")
        @DecimalMin(
                value = "0.01",
                message = "El precio del producto debe ser mayor o igual a 0.01"
        )
        @Digits(
                integer = 10,
                fraction = 2,
                message = "El precio del producto debe tener hasta 10 enteros y 2 decimales"
        )
        BigDecimal price
) {
}