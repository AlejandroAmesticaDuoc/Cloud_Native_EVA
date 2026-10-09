package cl.duoc.pedidos360.notify.config;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de RabbitMQ y política del consumidor (claves {@code messaging.*} de application.properties).
 * Topología, listener y manejador de ACK los leen desde aquí; no hay nombres escritos en el código.
 */
@Validated
@ConfigurationProperties("messaging")
public record MessagingProperties(
        @Valid @NotNull Exchanges exchanges,
        @Valid @NotNull Routes routes,
        @Valid @NotNull Queues queues,
        @Valid @NotNull Consumer consumer) {

    public record Exchanges(@NotBlank String direct, @NotBlank String topic, @NotBlank String deadLetter) {}

    /** Notify solo consume la ruta de correo. */
    public record Routes(@Valid @NotNull Route email) {}

    public record Route(@NotBlank String queue, @NotBlank String dlq, @NotBlank String routingKey,
            @NotBlank String topicPattern) {}

    public record Queues(@Positive long maxLength, @NotNull Duration deadLetterTtl, @Positive long deadLetterMaxLength) {}

    /**
     * prefetch: mensajes sin confirmar por consumidor; maxPayloadBytes: tamaño máximo aceptado;
     * idempotencyCacheSize: eventIds recordados para descartar duplicados; retry: reintentos locales.
     */
    public record Consumer(@Positive int prefetch, @Positive int maxPayloadBytes, @Positive int idempotencyCacheSize,
            @Valid @NotNull Retry retry) {}

    /** Reintento local acotado ante errores transitorios: intentos totales y espera exponencial con tope. */
    public record Retry(@Positive int maxAttempts, @NotNull Duration initialInterval,
            @DecimalMin("1.0") double multiplier, @NotNull Duration maxInterval) {}
}
