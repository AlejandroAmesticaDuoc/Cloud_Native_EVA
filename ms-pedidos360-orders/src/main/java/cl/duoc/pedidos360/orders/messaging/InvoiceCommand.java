package cl.duoc.pedidos360.orders.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payload v1 de {@code invoice.gen}: líneas con el precio histórico del pedido y su total. */
public record InvoiceCommand(int schemaVersion, UUID eventId, long orderId, String customerId,
        List<Item> items, BigDecimal total, Instant occurredAt, String traceId) {
    public record Item(long productId, int quantity, BigDecimal unitPrice) {}
}
