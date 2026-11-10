package cl.duoc.pedidos360.audit.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import cl.duoc.pedidos360.audit.dto.*;
import cl.duoc.pedidos360.audit.exception.InvalidEventException;
import cl.duoc.pedidos360.audit.messaging.EventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditStore {
    private static final Logger log = LoggerFactory.getLogger(AuditStore.class);
    private final JdbcTemplate jdbc;
    private final EventParser parser;
    private final JsonMapper json;
    private final ObjectProvider<TimelinePublisher> timeline;

    public AuditStore(JdbcTemplate jdbc, EventParser parser, JsonMapper json, ObjectProvider<TimelinePublisher> timeline) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.json = json;
        this.timeline = timeline;
    }

    /**
     * Registra un evento de orders.events ya validado. Es idempotente: el mismo evento repetido no se vuelve a
     * guardar ni a publicar. Lanza {@link InvalidEventException} si la clave no es el pedido o si el eventId o la
     * versión del pedido ya existen con otro contenido. Si el evento es nuevo, publica su entrada en
     * audit.timeline dentro de la misma transacción: si Kafka falla, el registro se revierte y se reintenta.
     *
     * @return true si el evento era nuevo
     */
    @Transactional
    public boolean record(String key, OrderEvent event) {
        if (!String.valueOf(event.orderId()).equals(key)) {
            throw new InvalidEventException("INVALID_EVENT", "La clave Kafka no coincide con orderId " + event.orderId());
        }
        String payload = parser.serialize(event);
        int inserted = jdbc.update("""
                INSERT INTO audit_events(event_id, order_id, aggregate_version, payload)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, event.eventId(), event.orderId(), event.aggregateVersion(), payload);
        if (inserted == 0) {
            var existing = jdbc.queryForList("SELECT payload FROM audit_events WHERE event_id = ?", String.class, event.eventId());
            if (existing.size() != 1 || !existing.getFirst().equals(payload)) {
                throw new InvalidEventException("EVENT_CONFLICT", "El evento " + event.eventId()
                        + " o la versión " + event.aggregateVersion() + " del pedido ya existen con otro contenido");
            }
            log.info("Evento duplicado ignorado eventId={} orderId={} traceId={}", event.eventId(), event.orderId(), event.traceId());
            return false;
        }
        timeline.ifAvailable(publisher -> publisher.publish(AuditTimelineEntry.from(event, Instant.now())));
        return true;
    }

    /** Deja constancia de un mensaje rechazado (sin guardar su contenido, solo su hash SHA-256). */
    public void reject(String topic, int partition, long offset, String reason, byte[] payload) {
        jdbc.update("""
                INSERT INTO audit_rejections(source_topic, source_partition, source_offset, reason, payload_sha256)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, topic, partition, offset, reason, digest(payload));
    }

    @Transactional(readOnly = true)
    public AuditPage find(Long orderId, long afterId, int size) {
        if (afterId < 0 || size < 1 || size > 100 || (orderId != null && orderId < 1)) {
            throw new IllegalArgumentException("Consulta inválida");
        }
        String sql = "SELECT id, payload, recorded_at FROM audit_events WHERE id > ?"
                + (orderId == null ? "" : " AND order_id = ?") + " ORDER BY id LIMIT ?";
        Object[] arguments = orderId == null ? new Object[] {afterId, size + 1}
                : new Object[] {afterId, orderId, size + 1};
        var rows = jdbc.query(sql, (rs, number) -> new AuditEntry(rs.getLong("id"),
                json.readValue(rs.getString("payload"), OrderEvent.class),
                rs.getTimestamp("recorded_at").toInstant()), arguments);
        boolean more = rows.size() > size;
        var items = java.util.List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new AuditPage(items, more ? items.getLast().id() : null);
    }

    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
