package cl.duoc.pedidos360.bff.dto.order;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        Integer quantity,
        BigDecimal unitPrice
) {
}