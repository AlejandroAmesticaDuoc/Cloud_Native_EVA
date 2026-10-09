package cl.duoc.pedidos360.mqadmin.dto;

import java.util.Map;

/**
 * Estado de una cola según RabbitMQ. {@code protectedResource} indica que pertenece a la topología
 * base del contrato y que la API no permite eliminarla.
 */
public record QueueResponse(
        String name, String type, boolean durable, boolean autoDelete, Map<String, Object> arguments,
        long messages, long messagesReady, long messagesUnacknowledged, long consumers,
        boolean protectedResource) {
}
