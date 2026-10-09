package cl.duoc.pedidos360.mqadmin.client;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Binding según {@code GET /api/bindings/{vhost}}; {@code source} vacío es el exchange por defecto. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManagementBinding(
        String source,
        String destination,
        @JsonProperty("destination_type") String destinationType,
        @JsonProperty("routing_key") String routingKey,
        Map<String, Object> arguments) {

    public boolean targetsQueue() {
        return "queue".equals(destinationType);
    }
}
