package cl.duoc.pedidos360.notify.dto;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record EmailCommand(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull UUID eventId,
        @NotNull @Positive Long orderId,
        @NotBlank @Size(max = 200) String customerId,
        @NotNull OrderStatus status,
        @NotNull Instant occurredAt,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String traceId) {
    public enum OrderStatus { CREADO, ACEPTADO, EN_PREPARACION, DESPACHADO, ENTREGADO, CANCELADO }
}
