package cl.duoc.pedidos360.report.dto;

import java.math.BigDecimal;

public record LeadTimeResponse(long deliveredOrders, BigDecimal averageSeconds,
        BigDecimal minimumSeconds, BigDecimal maximumSeconds) {}
