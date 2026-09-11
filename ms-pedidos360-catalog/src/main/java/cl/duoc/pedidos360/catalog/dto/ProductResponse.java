package cl.duoc.pedidos360.catalog.dto;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        Integer stock,
        Boolean active
) {
}