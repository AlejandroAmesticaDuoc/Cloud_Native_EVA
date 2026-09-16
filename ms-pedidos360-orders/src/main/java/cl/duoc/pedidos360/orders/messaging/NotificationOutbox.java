package cl.duoc.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.orders.entity.PurchaseOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class NotificationOutbox {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public NotificationOutbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PurchaseOrder order, String traceId) {
        var command = new EmailCommand(1, UUID.randomUUID(), order.getId(), order.getCustomerId(),
                order.getStatus(), Instant.now(), traceId);
        jdbc.update("INSERT INTO notification_outbox (event_id, order_id, payload) VALUES (?, ?, ?)",
                command.eventId().toString(), command.orderId(), json.writeValueAsString(command));
    }
}
