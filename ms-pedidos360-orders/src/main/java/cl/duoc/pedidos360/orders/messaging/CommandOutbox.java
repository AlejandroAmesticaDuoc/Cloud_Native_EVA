package cl.duoc.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cl.duoc.pedidos360.orders.entity.PurchaseOrder;
import cl.duoc.pedidos360.orders.service.OrderCommands;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox transaccional de comandos: una fila por comando en {@code notification_outbox}, dentro de la misma
 * transacción del pedido. OutboxPublisher los publica después; si el pedido se revierte, no queda ningún comando.
 */
@Service
public class CommandOutbox implements OrderCommands {
    private static final int SCHEMA_VERSION = 1;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public CommandOutbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PurchaseOrder order, List<CommandType> commands, String traceId) {
        Instant occurredAt = Instant.now();
        for (CommandType type : commands) {
            UUID eventId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO notification_outbox (event_id, order_id, command_type, payload) VALUES (?, ?, ?, ?)
                    """, eventId.toString(), order.getId(), type.name(),
                    json.writeValueAsString(payload(type, eventId, order, occurredAt, traceId)));
        }
    }

    private Object payload(CommandType type, UUID eventId, PurchaseOrder order, Instant occurredAt, String traceId) {
        return switch (type) {
            case EMAIL, EMAIL_PRIORITY -> new EmailCommand(SCHEMA_VERSION, eventId, order.getId(),
                    order.getCustomerId(), order.getStatus(), occurredAt, traceId);
            case KITCHEN_TICKET -> new KitchenTicketCommand(SCHEMA_VERSION, eventId, order.getId(),
                    order.getCustomerId(), order.getItems().stream().map(item ->
                            new KitchenTicketCommand.Item(item.getProductId(), item.getQuantity())).toList(),
                    occurredAt, traceId);
            case INVOICE -> new InvoiceCommand(SCHEMA_VERSION, eventId, order.getId(), order.getCustomerId(),
                    order.getItems().stream().map(item -> new InvoiceCommand.Item(item.getProductId(),
                            item.getQuantity(), item.getUnitPrice())).toList(), order.getTotal(), occurredAt, traceId);
        };
    }
}
