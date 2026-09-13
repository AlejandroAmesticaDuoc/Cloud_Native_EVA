package cl.duoc.pedidos360.bff.dto.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SummaryResponse(long totalOrders, long activeOrders, long deliveredOrders, long cancelledOrders,
        Map<String, Long> ordersByStatus, BigDecimal deliveredAmount,
        Instant hourlyFrom, Instant hourlyTo, List<HourlySales> salesByHour) {
    public record HourlySales(Instant hour, long deliveredOrders, BigDecimal deliveredAmount) {}
}
