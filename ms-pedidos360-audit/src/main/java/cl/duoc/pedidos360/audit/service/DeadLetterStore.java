package cl.duoc.pedidos360.audit.service;

import java.sql.Timestamp;
import cl.duoc.pedidos360.audit.dto.DeadLetter;
import cl.duoc.pedidos360.audit.dto.DeadLetterEntry;
import cl.duoc.pedidos360.audit.dto.DeadLetterPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Historial de dead letters de los comandos RabbitMQ. No conoce RabbitMQ: recibe los datos ya extraídos.
 * Idempotente por (message_id, source_queue, death_count): una reentrega no duplica el registro, pero un nuevo
 * rechazo del mismo mensaje tras un reproceso (conteo mayor) sí queda registrado.
 */
@Service
public class DeadLetterStore {
    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterStore.class);
    private final JdbcTemplate jdbc;

    public DeadLetterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Devuelve false si la dead letter ya estaba registrada. */
    @Transactional
    public boolean record(DeadLetter deadLetter) {
        int inserted = jdbc.update("""
                INSERT INTO audit_dead_letters(message_id, source_queue, reason, death_count, original_exchange,
                    original_routing_key, message_type, correlation_id, trace_id, payload, payload_sha256,
                    payload_bytes, first_death_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (message_id, source_queue, death_count) DO NOTHING
                """, deadLetter.messageId(), deadLetter.sourceQueue(), deadLetter.reason(), deadLetter.deathCount(),
                deadLetter.originalExchange(), deadLetter.originalRoutingKey(), deadLetter.messageType(),
                deadLetter.correlationId(), deadLetter.traceId(), deadLetter.payload(), deadLetter.payloadSha256(),
                deadLetter.payloadBytes(), deadLetter.firstDeathAt() == null ? null : Timestamp.from(deadLetter.firstDeathAt()));
        if (inserted == 0) {
            LOG.info("Dead letter ya auditada; se omite messageId={} cola={} conteo={}", deadLetter.messageId(),
                    deadLetter.sourceQueue(), deadLetter.deathCount());
            return false;
        }
        LOG.warn("[DLQ] dead letter auditada cola={} motivo={} conteo={} exchange={} routingKey={} messageId={} type={} "
                + "correlationId={} traceId={} bytes={}", deadLetter.sourceQueue(), deadLetter.reason(),
                deadLetter.deathCount(), deadLetter.originalExchange(), deadLetter.originalRoutingKey(),
                deadLetter.messageId(), deadLetter.messageType(), deadLetter.correlationId(), deadLetter.traceId(),
                deadLetter.payloadBytes());
        return true;
    }

    /** Página por cursor (id creciente), igual que AuditStore.find. */
    @Transactional(readOnly = true)
    public DeadLetterPage find(long afterId, int size) {
        if (afterId < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Consulta inválida");
        var rows = jdbc.query("""
                SELECT id, message_id, source_queue, reason, death_count, original_exchange, original_routing_key,
                    message_type, correlation_id, trace_id, payload, payload_sha256, payload_bytes, first_death_at,
                    recorded_at
                FROM audit_dead_letters WHERE id > ? ORDER BY id LIMIT ?
                """, (rs, number) -> new DeadLetterEntry(rs.getLong("id"), rs.getString("message_id"),
                rs.getString("source_queue"), rs.getString("reason"), rs.getLong("death_count"),
                rs.getString("original_exchange"), rs.getString("original_routing_key"), rs.getString("message_type"),
                rs.getString("correlation_id"), rs.getString("trace_id"), rs.getString("payload"),
                rs.getString("payload_sha256"), rs.getInt("payload_bytes"),
                rs.getTimestamp("first_death_at") == null ? null : rs.getTimestamp("first_death_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant()), afterId, size + 1);
        boolean more = rows.size() > size;
        var items = java.util.List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new DeadLetterPage(items, more ? items.getLast().id() : null);
    }
}
