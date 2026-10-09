package cl.duoc.pedidos360.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Payload v1 de {@code invoice.gen} publicado por orders al entregar un pedido (campos exactos del contrato). */
public record InvoiceCommand(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull UUID eventId,
        @NotNull @Positive Long orderId,
        @NotBlank @Size(max = 200) String customerId,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal total,
        @NotNull Instant occurredAt,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String traceId) {

    public record Item(
            @NotNull @Positive Long productId,
            @NotNull @Positive Integer quantity,
            @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {}
}
