package cl.duoc.pedidos360.catalog.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record StockDeductionRequest(
        @NotNull @Positive Long orderId,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull @Positive Long productId, @NotNull @Positive Integer quantity) {
    }
}
