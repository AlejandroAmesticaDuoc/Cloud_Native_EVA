package cl.duoc.pedidos360.orders.service;

import java.util.List;
import cl.duoc.pedidos360.orders.dto.OrderStatus;
import cl.duoc.pedidos360.orders.messaging.CommandType;

/**
 * Regla de negocio que decide qué comandos genera cada evento del pedido:
 * creación y cambios de estado -> EMAIL; CANCELADO -> EMAIL_PRIORITY (en lugar de EMAIL);
 * ACEPTADO -> EMAIL + KITCHEN_TICKET; ENTREGADO -> EMAIL + INVOICE.
 */
public final class OrderCommandPolicy {
    private OrderCommandPolicy() {}

    public static List<CommandType> commandsFor(OrderStatus previous, OrderStatus current) {
        if (previous == null) return List.of(CommandType.EMAIL);
        return switch (current) {
            case CANCELADO -> List.of(CommandType.EMAIL_PRIORITY);
            case ACEPTADO -> List.of(CommandType.EMAIL, CommandType.KITCHEN_TICKET);
            case ENTREGADO -> List.of(CommandType.EMAIL, CommandType.INVOICE);
            default -> List.of(CommandType.EMAIL);
        };
    }
}
