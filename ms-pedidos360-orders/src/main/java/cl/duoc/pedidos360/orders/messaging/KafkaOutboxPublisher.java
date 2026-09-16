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
@ConditionalOnProperty(name = "orders.events.enabled", havingValue = "true")
public class KafkaOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaEventSender sender;
    private final TransactionTemplate transactions;

    public KafkaOutboxPublisher(JdbcTemplate jdbc, KafkaEventSender sender, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.sender = sender;
        transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${orders.events.poll-delay:1000}")
    public void publishNext() {
        transactions.executeWithoutResult(tx -> {
            var pending = jdbc.query("""
                    SELECT e.id, e.event_id, e.order_id, e.payload, e.attempts FROM order_event_outbox e
                    WHERE e.published_at IS NULL AND e.next_attempt_at <= CURRENT_TIMESTAMP
                    AND NOT EXISTS (
                        SELECT 1 FROM order_event_outbox earlier WHERE earlier.order_id = e.order_id
                        AND earlier.aggregate_version < e.aggregate_version AND earlier.published_at IS NULL
                    )
                    ORDER BY e.id LIMIT 1 FOR UPDATE OF e SKIP LOCKED
                    """, (row, number) -> new Pending(row.getLong("id"), row.getString("event_id"),
                            row.getLong("order_id"), row.getString("payload"), row.getInt("attempts")));
            if (pending.isEmpty()) return;
            var event = pending.getFirst();
            try {
                sender.send(event.orderId(), event.payload());
                jdbc.update("UPDATE order_event_outbox SET published_at = CURRENT_TIMESTAMP WHERE id = ?", event.id());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempts = Math.min(event.attempts() + 1, 1000000);
                long delay = Math.min(60, 1L << Math.min(attempts, 6));
                jdbc.update("""
                        UPDATE order_event_outbox SET attempts = ?,
                        next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second') WHERE id = ?
                        """, attempts, delay, event.id());
                log.warn("Evento Kafka pendiente eventId={} intento={}", event.eventId(), attempts);
            }
        });
    }

    private record Pending(long id, String eventId, long orderId, String payload, int attempts) {}
}
