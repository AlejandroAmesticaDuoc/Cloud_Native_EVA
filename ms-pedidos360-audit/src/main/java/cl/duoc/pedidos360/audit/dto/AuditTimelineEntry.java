package cl.duoc.pedidos360.audit.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Entrada v1 del tópico audit.timeline: quién (actorId), qué (action y cambio de estado), cuándo (occurredAt)
 * y desde dónde (origin y traceId) para cada evento de pedido registrado por Audit. La clave Kafka es orderId;
 * el tópico es compactado, así que conserva al menos la última entrada de cada pedido.
 * entryId es determinista (derivado de sourceEventId): una reentrega produce la misma entrada.
 */
public record AuditTimelineEntry(
        int schemaVersion,
        UUID entryId,
        UUID sourceEventId,
        long orderId,
        String actorId,
        OrderEvent.EventType action,
        OrderEvent.Status previousStatus,
        OrderEvent.Status status,
        Instant occurredAt,
        Instant recordedAt,
        String traceId,
        String origin) {

    public static final String ORIGIN = "ms-pedidos360-orders";

    public static AuditTimelineEntry from(OrderEvent event, Instant recordedAt) {
        UUID entryId = UUID.nameUUIDFromBytes(("audit.timeline:" + event.eventId()).getBytes(StandardCharsets.UTF_8));
        return new AuditTimelineEntry(1, entryId, event.eventId(), event.orderId(), event.actorId(), event.eventType(),
                event.previousStatus(), event.status(), event.occurredAt(), recordedAt, event.traceId(), ORIGIN);
    }
}
