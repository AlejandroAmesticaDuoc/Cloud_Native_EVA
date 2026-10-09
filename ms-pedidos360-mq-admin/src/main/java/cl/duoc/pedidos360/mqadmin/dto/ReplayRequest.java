package cl.duoc.pedidos360.mqadmin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Cantidad máxima de mensajes que se reenvían desde una DLQ en una llamada. */
public record ReplayRequest(
        @Schema(example = "10", minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "maxMessages es obligatorio (1 a 100)")
        @Min(value = 1, message = "maxMessages debe ser mayor o igual a 1")
        @Max(value = 100, message = "maxMessages no puede superar 100")
        Integer maxMessages) {
}
