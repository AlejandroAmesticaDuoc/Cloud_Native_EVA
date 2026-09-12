package cl.duoc.pedidos360.orders.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.orders.dto.OrderStatus;

public record OrderEvent(int schemaVersion, UUID eventId, EventType eventType, long orderId,
        long aggregateVersion, Instant occurredAt, String traceId, String actorId, String customerId,
        OrderStatus previousStatus, OrderStatus status, Instant createdAt, BigDecimal total) {
    public enum EventType { OrderCreated, OrderAccepted, OrderStatusChanged, OrderCancelled }

    public static EventType typeFor(OrderStatus previous, OrderStatus status) {
        if (previous == null) return EventType.OrderCreated;
        return switch (status) {
            case ACEPTADO -> EventType.OrderAccepted;
            case CANCELADO -> EventType.OrderCancelled;
            default -> EventType.OrderStatusChanged;
        };
    }
}
