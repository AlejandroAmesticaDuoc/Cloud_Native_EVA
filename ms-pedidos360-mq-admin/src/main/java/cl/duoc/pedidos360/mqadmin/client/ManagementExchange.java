package cl.duoc.pedidos360.mqadmin.client;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Exchange según {@code GET /api/exchanges/{vhost}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManagementExchange(
        String name,
        String type,
        boolean durable,
        @JsonProperty("auto_delete") boolean autoDelete,
        boolean internal,
        Map<String, Object> arguments) {
}
