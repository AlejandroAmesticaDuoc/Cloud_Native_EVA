package cl.duoc.pedidos360.orders.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Callbacks del RabbitTemplate del productor. Los publisher confirms ({@code correlated}) y {@code mandatory=true}
 * se mantienen en application.properties; aquí solo se registran en logs las devoluciones y los nack del broker.
 * La decisión de reintentar la toma OutboxPublisher, que conserva el comando en la tabla outbox.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "orders.notifications.enabled", havingValue = "true")
public class RabbitPublisherConfig {
    private static final Logger log = LoggerFactory.getLogger(RabbitPublisherConfig.class);

    @Bean
    RabbitTemplateCustomizer publisherCallbacks() {
        return template -> {
            // mandatory=true: un mensaje sin cola de destino vuelve al productor en lugar de descartarse.
            template.setReturnsCallback(returned -> log.warn(
                    "[RabbitMQ] Comando devuelto sin ruta exchange={} routingKey={} replyCode={} replyText={} messageId={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText(),
                    returned.getMessage().getMessageProperties().getMessageId()));
            // El id de CorrelationData es el eventId (messageId) del comando.
            template.setConfirmCallback((correlation, ack, cause) -> {
                String messageId = correlation == null ? null : correlation.getId();
                if (ack) log.debug("[RabbitMQ] Confirmación del broker messageId={}", messageId);
                else log.warn("[RabbitMQ] El broker rechazó (nack) el comando messageId={} motivo={}", messageId, cause);
            });
        };
    }
}
