package cl.duoc.pedidos360.mqadmin.client;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cola según {@code GET /api/queues/{vhost}}. Las estadísticas pueden faltar en una cola recién
 * creada, por eso los contadores son opcionales. Se ignoran los demás campos de la API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManagementQueue(
        String name,
        String type,
        boolean durable,
        @JsonProperty("auto_delete") boolean autoDelete,
        Map<String, Object> arguments,
        Long messages,
        @JsonProperty("messages_ready") Long messagesReady,
        @JsonProperty("messages_unacknowledged") Long messagesUnacknowledged,
        Long consumers) {
}
