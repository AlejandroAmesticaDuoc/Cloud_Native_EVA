package cl.duoc.pedidos360.audit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import cl.duoc.pedidos360.audit.dto.*;
import cl.duoc.pedidos360.audit.messaging.EventParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditStore {
    private final JdbcTemplate jdbc;
    private final EventParser parser;
    private final JsonMapper json;

    public AuditStore(JdbcTemplate jdbc, EventParser parser, JsonMapper json) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.json = json;
    }

    @Transactional
    public void receive(ConsumerRecord<String, String> record) {
        OrderEvent event;
        try {
            event = parser.parse(record.key(), record.value());
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            reject(record, "INVALID_EVENT");
            return;
        }
        String payload = parser.serialize(event);
        int inserted = jdbc.update("""
                INSERT INTO audit_events(event_id, order_id, aggregate_version, payload)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, event.eventId(), event.orderId(), event.aggregateVersion(), payload);
        if (inserted == 0) {
            var existing = jdbc.queryForList("SELECT payload FROM audit_events WHERE event_id = ?", String.class, event.eventId());
            if (existing.size() != 1 || !existing.getFirst().equals(payload)) reject(record, "EVENT_CONFLICT");
        }
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

    private void reject(ConsumerRecord<String, String> record, String reason) {
        jdbc.update("""
                INSERT INTO audit_rejections(source_topic, source_partition, source_offset, reason, payload_sha256)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, record.topic(), record.partition(), record.offset(), reason, digest(record.value()));
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
