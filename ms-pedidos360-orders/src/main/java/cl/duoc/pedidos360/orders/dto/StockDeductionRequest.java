package cl.duoc.pedidos360.orders.dto;

import java.util.List;

public record StockDeductionRequest(Long orderId, List<Item> items) {
    public record Item(Long productId, Integer quantity) {}
}
