package cl.duoc.pedidos360.audit.config;

import java.time.Duration;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de RabbitMQ y política del consumidor de auditoría (claves {@code messaging.*} de application.properties).
 * Topología, listener y manejador de ACK los leen desde aquí; no hay nombres escritos en el código.
 */
@Validated
@ConfigurationProperties("messaging")
public record MessagingProperties(
        @Valid @NotNull Exchanges exchanges,
        @Valid @NotNull DeadLetters deadLetters,
        @Valid @NotNull Queues queues,
        @Valid @NotNull Consumer consumer) {

    /** Audit solo usa el exchange de dead letters (cmd.dead.dlx). */
    public record Exchanges(@NotBlank String deadLetter) {}

    /**
     * queue/dlq: cola de auditoría y su propia DLQ; routingKey: clave con la que la cola de auditoría rechaza
     * hacia su DLQ; sourceRoutingKeys: rutas de comandos cuyas dead letters se copian a la auditoría.
     */
    public record DeadLetters(@NotBlank String queue, @NotBlank String dlq, @NotBlank String routingKey,
            @NotEmpty List<@NotBlank String> sourceRoutingKeys) {}

    public record Queues(@Positive long maxLength, @NotNull Duration deadLetterTtl, @Positive long deadLetterMaxLength) {}

    /** prefetch: mensajes sin confirmar; maxPayloadBytes: sobre este tamaño solo se guarda el hash; retry: reintentos. */
    public record Consumer(@Positive int prefetch, @Positive int maxPayloadBytes, @Valid @NotNull Retry retry) {}

    /** Reintento local acotado ante errores transitorios: intentos totales y espera exponencial con tope. */
    public record Retry(@Positive int maxAttempts, @NotNull Duration initialInterval,
            @DecimalMin("1.0") double multiplier, @NotNull Duration maxInterval) {}
}
