package cl.duoc.pedidos360.report.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import cl.duoc.pedidos360.report.dto.OrderEvent;
import cl.duoc.pedidos360.report.exception.InvalidEventException;
import cl.duoc.pedidos360.report.messaging.EventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportProjection {
    private static final Logger log = LoggerFactory.getLogger(ReportProjection.class);
    private final JdbcTemplate jdbc;
    private final EventParser parser;

    public ReportProjection(JdbcTemplate jdbc, EventParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    /**
     * Proyecta un evento de orders.events ya validado en report_orders (estado más reciente por pedido según
     * aggregateVersion). Es idempotente. Lanza {@link InvalidEventException} si la clave no es el pedido, si
     * occurredAt es anterior a createdAt o si el eventId/versión ya existen con otro contenido.
     *
     * @return true si el evento era nuevo
     */
    @Transactional
    public boolean record(String key, OrderEvent event) {
        if (!String.valueOf(event.orderId()).equals(key)) {
            throw new InvalidEventException("INVALID_EVENT", "La clave Kafka no coincide con orderId " + event.orderId());
        }
        if (event.occurredAt().isBefore(event.createdAt())) {
            throw new InvalidEventException("INVALID_EVENT", "occurredAt es anterior a createdAt en " + event.eventId());
        }
        String payload = parser.serialize(event);
        int inserted = jdbc.update("""
                INSERT INTO report_events(event_id, order_id, aggregate_version, payload)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, event.eventId(), event.orderId(), event.aggregateVersion(), payload);
        if (inserted == 0) {
            var existing = jdbc.queryForList("SELECT payload FROM report_events WHERE event_id = ?", String.class, event.eventId());
            if (existing.size() != 1 || !existing.getFirst().equals(payload)) {
                throw new InvalidEventException("EVENT_CONFLICT", "El evento " + event.eventId()
                        + " o la versión " + event.aggregateVersion() + " del pedido ya existen con otro contenido");
            }
            log.info("Evento duplicado ignorado eventId={} orderId={} traceId={}", event.eventId(), event.orderId(), event.traceId());
            return false;
        }
        jdbc.update("""
                INSERT INTO report_orders(order_id, aggregate_version, status, created_at, occurred_at, total)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE SET
                    aggregate_version = EXCLUDED.aggregate_version, status = EXCLUDED.status,
                    created_at = EXCLUDED.created_at, occurred_at = EXCLUDED.occurred_at, total = EXCLUDED.total
                WHERE report_orders.aggregate_version < EXCLUDED.aggregate_version
                """, event.orderId(), event.aggregateVersion(), event.status().name(),
                Timestamp.from(event.createdAt()), Timestamp.from(event.occurredAt()), event.total());
        return true;
    }

    /** Deja constancia de un mensaje rechazado de cualquier tópico (sin guardar su contenido, solo su hash). */
    public void reject(String topic, int partition, long offset, String reason, byte[] payload) {
        jdbc.update("""
                INSERT INTO report_rejections(source_topic, source_partition, source_offset, reason, payload_sha256)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, topic, partition, offset, reason, digest(payload));
    }

    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
