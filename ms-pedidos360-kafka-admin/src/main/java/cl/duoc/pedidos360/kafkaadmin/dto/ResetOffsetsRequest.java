package cl.duoc.pedidos360.kafkaadmin.dto;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Reinicio de offsets de un consumer group sobre un tópico (el grupo debe estar sin consumidores activos). */
public record ResetOffsetsRequest(
        @Schema(example = "orders.events", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El tópico es obligatorio")
        @Pattern(regexp = ResourceNames.READABLE_TOPIC, message = "El nombre del tópico " + ResourceNames.READABLE_TOPIC_RULE)
        String topic,

        @Schema(example = "EARLIEST", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "strategy es obligatorio (EARLIEST, LATEST o TIMESTAMP)")
        OffsetResetStrategy strategy,

        @Schema(example = "2026-11-10T12:00:00Z", description = "Obligatorio solo con strategy=TIMESTAMP")
        Instant timestamp) {

    @Schema(hidden = true)
    @AssertTrue(message = "timestamp es obligatorio con strategy=TIMESTAMP y no se usa con las demás estrategias")
    public boolean isTimestampConsistent() {
        return strategy == null || (strategy == OffsetResetStrategy.TIMESTAMP) == (timestamp != null);
    }
}
