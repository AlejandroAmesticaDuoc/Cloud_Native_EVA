package cl.duoc.pedidos360.orders.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record CreateOrderRequest(
        @NotBlank @Size(max = 200) String customerId,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull @Positive Long productId, @NotNull @Positive Integer quantity) {}
}
