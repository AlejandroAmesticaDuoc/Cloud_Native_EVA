package cl.duoc.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payload v1 de {@code kitchen.ticket}: productos y cantidades que cocina debe preparar. */
public record KitchenTicketCommand(int schemaVersion, UUID eventId, long orderId, String customerId,
        List<Item> items, Instant occurredAt, String traceId) {
    public record Item(long productId, int quantity) {}
}
