package cl.duoc.pedidos360.mqadmin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Solicitud para crear una cola. Los campos opcionales toman valores seguros por defecto:
 * CLASSIC, durable y sin autoeliminación.
 */
@ValidQueueSettings
public record CreateQueueRequest(
        @Schema(example = "q.demo.ep3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El nombre de la cola es obligatorio")
        @Pattern(regexp = ResourceNames.NAME, message = "El nombre de la cola " + ResourceNames.NAME_RULE)
        String name,

        @Schema(defaultValue = "CLASSIC")
        QueueType type,

        @Schema(defaultValue = "true")
        Boolean durable,

        @Schema(defaultValue = "false")
        Boolean autoDelete,

        @Schema(example = "cmd.dead.dlx", description = "Exchange que recibe los mensajes rechazados o expirados; debe existir")
        @Pattern(regexp = ResourceNames.NAME, message = "deadLetterExchange " + ResourceNames.NAME_RULE)
        String deadLetterExchange,

        @Schema(example = "demo.failed", description = "Routing key de dead letter; requiere deadLetterExchange")
        @Size(max = 255, message = "deadLetterRoutingKey no puede superar 255 caracteres")
        @Pattern(regexp = ResourceNames.ROUTING_KEY, message = "deadLetterRoutingKey " + ResourceNames.ROUTING_KEY_RULE)
        String deadLetterRoutingKey,

        @Schema(example = "604800000", description = "TTL de los mensajes en milisegundos (1 a 1209600000, 14 días)")
        @Min(value = 1, message = "messageTtlMs debe ser mayor o igual a 1 ms")
        @Max(value = 1_209_600_000, message = "messageTtlMs no puede superar 1209600000 ms (14 días)")
        Integer messageTtlMs,

        @Schema(example = "10000", description = "Cantidad máxima de mensajes en la cola (1 a 1000000)")
        @Min(value = 1, message = "maxLength debe ser mayor o igual a 1")
        @Max(value = 1_000_000, message = "maxLength no puede superar 1000000 mensajes")
        Integer maxLength) {

    public CreateQueueRequest {
        type = type == null ? QueueType.CLASSIC : type;
        durable = durable == null ? Boolean.TRUE : durable;
        autoDelete = autoDelete == null ? Boolean.FALSE : autoDelete;
    }
}
