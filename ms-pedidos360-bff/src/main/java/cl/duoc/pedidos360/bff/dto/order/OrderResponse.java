package cl.duoc.pedidos360.bff.dto.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String customerId,
        OrderStatus status,
        Instant createdAt,
        List<OrderItemResponse> items,
        BigDecimal total
) {
}