package cl.duoc.pedidos360.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import cl.duoc.pedidos360.report.dto.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportsService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReportsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SummaryResponse summary(int hours) {
        if (hours < 1 || hours > 168) throw new IllegalArgumentException("hours debe estar entre 1 y 168");
        Instant to = clock.instant().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(hours, ChronoUnit.HOURS);
        var counts = new EnumMap<OrderEvent.Status, Long>(OrderEvent.Status.class);
        for (var status : OrderEvent.Status.values()) counts.put(status, 0L);
        var totals = new EnumMap<OrderEvent.Status, BigDecimal>(OrderEvent.Status.class);
        jdbc.query("SELECT status, COUNT(*) AS quantity, SUM(total) AS amount FROM report_orders GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    var status = OrderEvent.Status.valueOf(rs.getString("status"));
                    counts.put(status, rs.getLong("quantity"));
                    totals.put(status, rs.getBigDecimal("amount"));
                });
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long delivered = counts.get(OrderEvent.Status.ENTREGADO);
        long cancelled = counts.get(OrderEvent.Status.CANCELADO);
        var byHour = new HashMap<Instant, SummaryResponse.HourlySales>();
        jdbc.query("""
                SELECT date_trunc('hour', occurred_at, 'UTC') AS hour,
                       COUNT(*) AS quantity, SUM(total) AS amount
                FROM report_orders
                WHERE status = 'ENTREGADO' AND occurred_at >= ? AND occurred_at < ?
                GROUP BY hour ORDER BY hour
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Instant hour = rs.getTimestamp("hour").toInstant();
                    byHour.put(hour, new SummaryResponse.HourlySales(hour, rs.getLong("quantity"),
                            rs.getBigDecimal("amount").setScale(2)));
                }, Timestamp.from(from), Timestamp.from(to));
        var sales = new ArrayList<SummaryResponse.HourlySales>();
        for (int i = 0; i < hours; i++) {
            Instant hour = from.plus(i, ChronoUnit.HOURS);
            sales.add(byHour.getOrDefault(hour, new SummaryResponse.HourlySales(hour, 0, new BigDecimal("0.00"))));
        }
        return new SummaryResponse(total, total - delivered - cancelled, delivered, cancelled,
                java.util.Collections.unmodifiableMap(counts),
                totals.getOrDefault(OrderEvent.Status.ENTREGADO, new BigDecimal("0.00")).setScale(2),
                from, to, java.util.List.copyOf(sales));
    }

    @Transactional(readOnly = true)
    public LeadTimeResponse leadTime() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS quantity,
                       AVG(EXTRACT(EPOCH FROM (occurred_at - created_at))) AS average_seconds,
                       MIN(EXTRACT(EPOCH FROM (occurred_at - created_at))) AS minimum_seconds,
                       MAX(EXTRACT(EPOCH FROM (occurred_at - created_at))) AS maximum_seconds
                FROM report_orders WHERE status = 'ENTREGADO'
                """, (rs, row) -> new LeadTimeResponse(rs.getLong("quantity"),
                seconds(rs.getBigDecimal("average_seconds")), seconds(rs.getBigDecimal("minimum_seconds")),
                seconds(rs.getBigDecimal("maximum_seconds"))));
    }

    private BigDecimal seconds(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }
}
