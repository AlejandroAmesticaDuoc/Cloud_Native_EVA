package cl.duoc.pedidos360.report.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import cl.duoc.pedidos360.report.dto.ActivityResponse;
import cl.duoc.pedidos360.report.dto.AuditTimelineEntry;
import cl.duoc.pedidos360.report.dto.OrderEvent;
import cl.duoc.pedidos360.report.exception.InvalidEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Métricas de actividad en tiempo real a partir de audit.timeline (acciones por tipo, actor y minuto). */
@Service
public class ActivityMetricsService {
    private static final Logger log = LoggerFactory.getLogger(ActivityMetricsService.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ActivityMetricsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Registra una entrada de la línea de tiempo. Es idempotente por entryId (Kafka puede reentregar y el tópico
     * compactado puede repetir la última entrada de un pedido).
     *
     * @return true si la entrada era nueva
     */
    @Transactional
    public boolean record(String key, AuditTimelineEntry entry) {
        if (!String.valueOf(entry.orderId()).equals(key)) {
            throw new InvalidEventException("INVALID_EVENT", "La clave Kafka no coincide con orderId " + entry.orderId());
        }
        int inserted = jdbc.update("""
                INSERT INTO report_activity(entry_id, source_event_id, order_id, actor_id, action, status,
                                            occurred_at, recorded_at, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, entry.entryId(), entry.sourceEventId(), entry.orderId(), entry.actorId(), entry.action().name(),
                entry.status().name(), Timestamp.from(entry.occurredAt()), Timestamp.from(entry.recordedAt()), entry.traceId());
        if (inserted == 0) log.info("Entrada de línea de tiempo duplicada ignorada entryId={} orderId={}", entry.entryId(), entry.orderId());
        return inserted == 1;
    }

    @Transactional(readOnly = true)
    public ActivityResponse activity(int minutes) {
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("minutes debe estar entre 1 y 1440");
        Instant to = clock.instant().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        Instant from = to.minus(minutes, ChronoUnit.MINUTES);
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        var byAction = new EnumMap<OrderEvent.EventType, Long>(OrderEvent.EventType.class);
        for (var type : OrderEvent.EventType.values()) byAction.put(type, 0L);
        jdbc.query("SELECT action, COUNT(*) AS events FROM report_activity WHERE occurred_at >= ? AND occurred_at < ? GROUP BY action",
                (RowCallbackHandler) rs -> byAction.put(OrderEvent.EventType.valueOf(rs.getString("action")), rs.getLong("events")),
                start, end);
        long total = byAction.values().stream().mapToLong(Long::longValue).sum();
        var actors = jdbc.query("""
                SELECT actor_id, COUNT(*) AS events FROM report_activity
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY actor_id ORDER BY events DESC, actor_id LIMIT 10
                """, (rs, row) -> new ActivityResponse.ActorActivity(rs.getString("actor_id"), rs.getLong("events")), start, end);
        var perMinute = jdbc.query("""
                SELECT date_trunc('minute', occurred_at, 'UTC') AS minute, COUNT(*) AS events FROM report_activity
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY minute ORDER BY minute
                """, (rs, row) -> new ActivityResponse.MinuteActivity(rs.getTimestamp("minute").toInstant(), rs.getLong("events")),
                start, end);
        Timestamp last = jdbc.queryForObject("SELECT MAX(recorded_at) FROM report_activity", Timestamp.class);
        return new ActivityResponse(from, to, total, Collections.unmodifiableMap(byAction), actors, perMinute,
                last == null ? null : last.toInstant());
    }
}
