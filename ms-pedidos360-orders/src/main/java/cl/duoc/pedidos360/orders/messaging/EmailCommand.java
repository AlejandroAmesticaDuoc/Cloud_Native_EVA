package cl.duoc.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.orders.dto.OrderStatus;

public record EmailCommand(int schemaVersion, UUID eventId, long orderId, String customerId,
        OrderStatus status, Instant occurredAt, String traceId) {}
