package cl.duoc.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.orders.dto.OrderStatus;
import cl.duoc.pedidos360.orders.entity.PurchaseOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OrderEventOutbox {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OrderEventOutbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PurchaseOrder order, OrderStatus previous, String actorId, String traceId) {
        var event = new OrderEvent(1, UUID.randomUUID(), OrderEvent.typeFor(previous, order.getStatus()),
                order.getId(), order.nextEventVersion(), Instant.now(), traceId, actorId, order.getCustomerId(),
                previous, order.getStatus(), order.getCreatedAt(), order.getTotal());
        jdbc.update("""
                INSERT INTO order_event_outbox (event_id, order_id, aggregate_version, payload)
                VALUES (?, ?, ?, ?)
                """, event.eventId().toString(), event.orderId(), event.aggregateVersion(), json.writeValueAsString(event));
    }
}
