package cl.duoc.pedidos360.orders.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, String customerId, OrderStatus status, Instant createdAt,
                            List<Item> items, BigDecimal total) {
    public record Item(Long productId, Integer quantity, BigDecimal unitPrice) {}
}
