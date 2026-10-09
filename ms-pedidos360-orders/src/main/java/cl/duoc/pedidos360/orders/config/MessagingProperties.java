package cl.duoc.pedidos360.orders.config;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de exchanges, colas y routing keys de RabbitMQ (claves {@code messaging.*} de application.properties).
 * Es la única fuente de nombres: publicadores y topología los leen desde aquí, nunca como literales.
 */
@Validated
@ConfigurationProperties("messaging")
public record MessagingProperties(
        @Valid @NotNull Exchanges exchanges,
        @Valid @NotNull Routes routes,
        @Valid @NotNull Queues queues) {

    /** cmd.direct (direct), cmd.topic (topic) y cmd.dead.dlx (direct, destino de los rechazos). */
    public record Exchanges(@NotBlank String direct, @NotBlank String topic, @NotBlank String deadLetter) {}

    /** Las tres rutas de comandos que publica orders. */
    public record Routes(@Valid @NotNull EmailRoute email, @Valid @NotNull Route kitchen, @Valid @NotNull Route invoice) {}

    /** Datos comunes de una ruta: cola principal, DLQ, routing key (direct y DLX) y patrón del exchange topic. */
    public interface CommandRoute {
        String queue();
        String dlq();
        String routingKey();
        String topicPattern();
    }

    public record Route(@NotBlank String queue, @NotBlank String dlq, @NotBlank String routingKey,
            @NotBlank String topicPattern) implements CommandRoute {}

    /** La ruta de correo agrega la routing key prioritaria que se publica por el exchange topic. */
    public record EmailRoute(@NotBlank String queue, @NotBlank String dlq, @NotBlank String routingKey,
            @NotBlank String topicPattern, @NotBlank String priorityRoutingKey) implements CommandRoute {}

    /** Política de las colas: largo máximo de las principales y retención de las DLQ. */
    public record Queues(@Positive long maxLength, @NotNull Duration deadLetterTtl, @Positive long deadLetterMaxLength) {}
}
