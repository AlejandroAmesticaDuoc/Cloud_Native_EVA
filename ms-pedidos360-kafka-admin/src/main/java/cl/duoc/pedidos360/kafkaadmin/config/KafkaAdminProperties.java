package cl.duoc.pedidos360.kafkaadmin.config;

import java.time.Duration;
import java.util.Set;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del administrador (prefijo kafkaadmin): tópicos protegidos, configuraciones permitidas,
 * límites de validación y timeout de cada operación contra el clúster.
 */
@Validated
@ConfigurationProperties("kafkaadmin")
public record KafkaAdminProperties(
        @NotNull Duration operationTimeout,
        @Min(1) @Max(1000) int maxPartitions,
        @Min(1) @Max(10) int maxReplicationFactor,
        @NotNull Set<String> protectedTopics,
        @NotEmpty Set<String> allowedConfigs) {

    /** Tópico base o interno de Kafka (__consumer_offsets, __transaction_state...). */
    public boolean isProtected(String topic) {
        return topic.startsWith("__") || protectedTopics.contains(topic);
    }
}
