package cl.duoc.pedidos360.audit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record OrderEvent(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull UUID eventId,
        @NotNull EventType eventType,
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long aggregateVersion,
        @NotNull Instant occurredAt,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String traceId,
        @NotBlank String actorId,
        @NotBlank @Size(max = 200) String customerId,
        Status previousStatus,
        @NotNull Status status,
        @NotNull Instant createdAt,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal total) {

    public enum EventType { OrderCreated, OrderAccepted, OrderStatusChanged, OrderCancelled }
    public enum Status { CREADO, ACEPTADO, EN_PREPARACION, DESPACHADO, ENTREGADO, CANCELADO }

    public boolean validTransition() {
        return switch (eventType) {
            case OrderCreated -> previousStatus == null && status == Status.CREADO && aggregateVersion == 1;
            case OrderAccepted -> previousStatus == Status.CREADO && status == Status.ACEPTADO;
            case OrderCancelled -> (previousStatus == Status.CREADO || previousStatus == Status.ACEPTADO)
                    && status == Status.CANCELADO;
            case OrderStatusChanged -> (previousStatus == Status.ACEPTADO && status == Status.EN_PREPARACION)
                    || (previousStatus == Status.EN_PREPARACION && status == Status.DESPACHADO)
                    || (previousStatus == Status.DESPACHADO && status == Status.ENTREGADO);
        };
    }
}
