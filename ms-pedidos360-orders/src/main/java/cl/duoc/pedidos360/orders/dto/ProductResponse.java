package cl.duoc.pedidos360.orders.dto;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, BigDecimal price, Integer stock, boolean active) {}
