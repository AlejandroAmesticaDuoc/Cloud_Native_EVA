package cl.duoc.pedidos360.orders.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "orders.notifications.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final RabbitEmailSender sender;
    private final TransactionTemplate transactions;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitEmailSender sender, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${orders.notifications.poll-delay:1000}")
    public void publishNext() {
        transactions.executeWithoutResult(tx -> {
            var pending = jdbc.query("""
                    SELECT id, event_id, payload, attempts FROM notification_outbox
                    WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (row, number) -> new Pending(row.getLong("id"), row.getString("event_id"),
                            row.getString("payload"), row.getInt("attempts")));
            if (pending.isEmpty()) return;
            var message = pending.getFirst();
            try {
                sender.send(message.eventId(), message.payload());
                jdbc.update("UPDATE notification_outbox SET published_at = CURRENT_TIMESTAMP WHERE id = ?", message.id());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempts = Math.min(message.attempts() + 1, 1000000);
                long delay = Math.min(60, 1L << Math.min(attempts, 6));
                jdbc.update("""
                        UPDATE notification_outbox SET attempts = ?,
                        next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second') WHERE id = ?
                        """, attempts, delay, message.id());
                log.warn("Notificación pendiente eventId={} intento={}", message.eventId(), attempts);
            }
        });
    }

    private record Pending(long id, String eventId, String payload, int attempts) {}
}
