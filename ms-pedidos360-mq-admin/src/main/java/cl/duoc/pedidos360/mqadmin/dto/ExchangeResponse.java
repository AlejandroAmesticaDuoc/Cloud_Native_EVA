package cl.duoc.pedidos360.mqadmin.dto;

import java.util.Map;

/** Exchange informado por RabbitMQ; el exchange por defecto del broker aparece con nombre vacío. */
public record ExchangeResponse(
        String name, String type, boolean durable, boolean autoDelete, boolean internal,
        Map<String, Object> arguments, boolean protectedResource) {
}
