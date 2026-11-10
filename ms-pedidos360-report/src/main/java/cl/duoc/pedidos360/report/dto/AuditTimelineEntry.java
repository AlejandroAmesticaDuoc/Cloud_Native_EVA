package cl.duoc.pedidos360.report.dto;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;

/** Entrada v1 de audit.timeline publicada por Audit (quién, qué, cuándo y desde dónde). Clave Kafka: orderId. */
public record AuditTimelineEntry(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull UUID entryId,
        @NotNull UUID sourceEventId,
        @NotNull @Positive Long orderId,
        @NotBlank @Size(max = 200) String actorId,
        @NotNull OrderEvent.EventType action,
        OrderEvent.Status previousStatus,
        @NotNull OrderEvent.Status status,
        @NotNull Instant occurredAt,
        @NotNull Instant recordedAt,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String traceId,
        @NotBlank @Size(max = 100) String origin) {}
