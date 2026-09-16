package cl.duoc.pedidos360.bff.dto.report;

import java.math.BigDecimal;

public record LeadTimeResponse(Long deliveredOrders, BigDecimal averageSeconds,
        BigDecimal minimumSeconds, BigDecimal maximumSeconds) {}
