package cl.duoc.pedidos360.bff.dto.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuditPage(List<AuditEntry> items, Long nextAfterId) {
    public record AuditEntry(long id, OrderEvent event, Instant recordedAt) {}
    public record OrderEvent(int schemaVersion, UUID eventId, String eventType, long orderId,
            long aggregateVersion, Instant occurredAt, String traceId, String actorId, String customerId,
            String previousStatus, String status, Instant createdAt, BigDecimal total) {}
}
