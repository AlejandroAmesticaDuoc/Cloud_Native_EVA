package cl.duoc.pedidos360.report.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import cl.duoc.pedidos360.report.dto.OrderEvent;
import cl.duoc.pedidos360.report.messaging.EventParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportProjection {
    private final JdbcTemplate jdbc;
    private final EventParser parser;

    public ReportProjection(JdbcTemplate jdbc, EventParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    @Transactional
    public void receive(ConsumerRecord<String, String> record) {
        OrderEvent event;
        try {
            event = parser.parse(record.key(), record.value());
            if (event.occurredAt().isBefore(event.createdAt())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            reject(record, "INVALID_EVENT");
            return;
        }
        String payload = parser.serialize(event);
        int inserted = jdbc.update("""
                INSERT INTO report_events(event_id, order_id, aggregate_version, payload)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, event.eventId(), event.orderId(), event.aggregateVersion(), payload);
        if (inserted == 0) {
            var existing = jdbc.queryForList("SELECT payload FROM report_events WHERE event_id = ?", String.class, event.eventId());
            if (existing.size() != 1 || !existing.getFirst().equals(payload)) reject(record, "EVENT_CONFLICT");
            return;
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
    }

    private void reject(ConsumerRecord<String, String> record, String reason) {
        jdbc.update("""
                INSERT INTO report_rejections(source_topic, source_partition, source_offset, reason, payload_sha256)
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
