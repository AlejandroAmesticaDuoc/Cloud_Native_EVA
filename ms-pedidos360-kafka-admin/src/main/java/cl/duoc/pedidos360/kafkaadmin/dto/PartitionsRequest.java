package cl.duoc.pedidos360.kafkaadmin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Nuevo total de particiones de un tópico. Kafka solo permite aumentarlas. */
public record PartitionsRequest(
        @Schema(example = "6", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "totalPartitions es obligatorio")
        @Min(value = 2, message = "totalPartitions debe ser mayor o igual a 2")
        @Max(value = ResourceNames.MAX_PARTITIONS, message = "totalPartitions no puede superar " + ResourceNames.MAX_PARTITIONS)
        Integer totalPartitions) {
}
