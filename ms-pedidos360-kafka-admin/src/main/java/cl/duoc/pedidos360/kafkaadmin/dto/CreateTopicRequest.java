package cl.duoc.pedidos360.kafkaadmin.dto;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Solicitud para crear un tópico con sus particiones, réplicas y configuraciones (retención, cleanup policy...). */
public record CreateTopicRequest(
        @Schema(example = "demo.ep5", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El nombre del tópico es obligatorio")
        @Pattern(regexp = ResourceNames.TOPIC, message = "El nombre del tópico " + ResourceNames.TOPIC_RULE)
        String name,

        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "partitions es obligatorio")
        @Min(value = 1, message = "partitions debe ser mayor o igual a 1")
        @Max(value = ResourceNames.MAX_PARTITIONS, message = "partitions no puede superar " + ResourceNames.MAX_PARTITIONS)
        Integer partitions,

        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Copias de cada partición; no puede superar la cantidad de brokers")
        @NotNull(message = "replicationFactor es obligatorio")
        @Min(value = 1, message = "replicationFactor debe ser mayor o igual a 1")
        @Max(value = ResourceNames.MAX_REPLICATION_FACTOR,
                message = "replicationFactor no puede superar " + ResourceNames.MAX_REPLICATION_FACTOR)
        Integer replicationFactor,

        @Schema(example = "{\"retention.ms\":\"86400000\",\"cleanup.policy\":\"delete\",\"min.insync.replicas\":\"2\"}",
                description = "Configuraciones permitidas: retention.ms, retention.bytes, cleanup.policy, min.insync.replicas, "
                        + "segment.ms, segment.bytes, max.message.bytes, compression.type, delete.retention.ms, "
                        + "min.compaction.lag.ms, max.compaction.lag.ms")
        @Size(max = 20, message = "configs admite como máximo 20 configuraciones")
        Map<@NotBlank(message = "La clave de configuración no puede estar vacía") String,
            @NotBlank(message = "El valor de configuración no puede estar vacío")
            @Size(max = 100, message = "El valor de configuración no puede superar 100 caracteres") String> configs) {

    public CreateTopicRequest {
        configs = configs == null ? Map.of() : Map.copyOf(configs);
    }
}
