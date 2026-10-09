package cl.duoc.pedidos360.mqadmin.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración centralizada de mq-admin (prefijo {@code mqadmin}). Ningún nombre de cola o
 * exchange se escribe en las clases de negocio: todos vienen de application.properties.
 *
 * @param managementUrl        URL base de la API HTTP de management de RabbitMQ.
 * @param managementTimeout    tiempo máximo de conexión y lectura hacia management.
 * @param replayConfirmTimeout espera máxima de la confirmación del broker al reenviar un mensaje.
 * @param deadLetterExchange   DLX del contrato; los mensajes muertos en colas que lo reciben
 *                             se reenvían directo a su cola para no duplicar copias.
 * @param protectedResources   colas y exchanges de la topología base que no se pueden borrar.
 * @param alerts               DLQ vigiladas, umbral e intervalo del monitor.
 */
@Validated
@ConfigurationProperties("mqadmin")
public record MqAdminProperties(
        @NotNull URI managementUrl,
        @NotNull Duration managementTimeout,
        @NotNull Duration replayConfirmTimeout,
        @NotBlank String deadLetterExchange,
        @NotEmpty Set<String> protectedResources,
        @Valid @NotNull Alerts alerts) {

    public boolean isProtected(String name) {
        return protectedResources.contains(name);
    }

    public boolean isDeadLetterQueue(String name) {
        return alerts.deadLetterQueues().contains(name);
    }

    /**
     * @param deadLetterQueues DLQ que se vigilan y que admiten replay.
     * @param threshold        cantidad de mensajes desde la que se emite la alerta.
     * @param interval         periodo entre revisiones del monitor.
     */
    public record Alerts(
            @NotEmpty List<String> deadLetterQueues,
            @Min(1) long threshold,
            @NotNull Duration interval) {
    }
}
