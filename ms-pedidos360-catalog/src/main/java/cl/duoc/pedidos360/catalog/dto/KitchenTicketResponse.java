package cl.duoc.pedidos360.catalog.dto;

import java.time.Instant;

public record KitchenTicketResponse(
        Long orderId,
        String customerId,
        Integer itemCount,
        String ticket,
        String traceId,
        Instant createdAt) {
}
