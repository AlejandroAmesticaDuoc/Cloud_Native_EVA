package cl.duoc.pedidos360.orders.service;

import java.util.List;
import cl.duoc.pedidos360.orders.entity.PurchaseOrder;
import cl.duoc.pedidos360.orders.messaging.CommandType;

/**
 * Puerto que usa OrdersService para pedir comandos asíncronos sin conocer RabbitMQ.
 * La implementación los guarda en la misma transacción que el cambio del pedido (outbox).
 */
public interface OrderCommands {
    void enqueue(PurchaseOrder order, List<CommandType> commands, String traceId);
}
