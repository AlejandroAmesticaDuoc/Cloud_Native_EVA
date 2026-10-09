package cl.duoc.pedidos360.orders.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica los comandos pendientes de la outbox, uno por ciclo y en orden de inserción.
 * Decisiones: éxito -> published_at; fallo -> WARN y nuevo intento con espera exponencial (máx. 60 s);
 * al agotar {@code orders.notifications.max-attempts} -> failed_at y ERROR con la excepción (requiere revisión).
 * {@code FOR UPDATE SKIP LOCKED} evita que dos instancias publiquen la misma fila.
 */
@Component
@ConditionalOnProperty(name = "orders.notifications.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final RabbitCommandPublisher publisher;
    private final TransactionTemplate transactions;
    private final int maxAttempts;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitCommandPublisher publisher, PlatformTransactionManager manager,
            @Value("${orders.notifications.max-attempts:20}") int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("orders.notifications.max-attempts debe ser mayor que 0");
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.transactions = new TransactionTemplate(manager);
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${orders.notifications.poll-delay:1000}")
    public void publishNext() {
        transactions.executeWithoutResult(tx -> {
            var pending = jdbc.query("""
                    SELECT id, event_id, command_type, payload, attempts FROM notification_outbox
                    WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (row, number) -> new Pending(row.getLong("id"), row.getString("event_id"),
                            CommandType.valueOf(row.getString("command_type")), row.getString("payload"),
                            row.getInt("attempts")));
            if (pending.isEmpty()) return;
            var command = pending.getFirst();
            try {
                publisher.publish(command.type(), command.eventId(), command.payload());
                jdbc.update("UPDATE notification_outbox SET published_at = CURRENT_TIMESTAMP WHERE id = ?", command.id());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempts = Math.min(command.attempts() + 1, 1000000);
                if (attempts >= maxAttempts) {
                    jdbc.update("UPDATE notification_outbox SET attempts = ?, failed_at = CURRENT_TIMESTAMP WHERE id = ?",
                            attempts, command.id());
                    log.error("[OUTBOX] Comando descartado tras {} intentos eventId={} tipo={}; requiere revisión manual",
                            attempts, command.eventId(), command.type(), exception);
                    return;
                }
                long delay = Math.min(60, 1L << Math.min(attempts, 6));
                jdbc.update("""
                        UPDATE notification_outbox SET attempts = ?,
                        next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second') WHERE id = ?
                        """, attempts, delay, command.id());
                log.warn("[OUTBOX] Comando pendiente eventId={} tipo={} intento={}/{} reintento en {} s motivo={}",
                        command.eventId(), command.type(), attempts, maxAttempts, delay, exception.getMessage());
            }
        });
    }

    private record Pending(long id, String eventId, CommandType type, String payload, int attempts) {}
}
