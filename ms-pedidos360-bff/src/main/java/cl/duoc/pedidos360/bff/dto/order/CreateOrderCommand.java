package cl.duoc.pedidos360.bff.dto.order;

import java.util.List;

public record CreateOrderCommand(
        String customerId,
        List<CreateOrderItemRequest> items
) {
}