package cl.duoc.pedidos360.mqadmin.dto;

import java.util.Map;

/** Binding exchange -> cola. */
public record BindingResponse(String exchange, String queue, String routingKey, Map<String, Object> arguments) {
}
