package cl.duoc.pedidos360.notify.config;

import cl.duoc.pedidos360.notify.messaging.support.JsonCommandReader;
import cl.duoc.pedidos360.notify.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.notify.messaging.support.ProcessedMessageRegistry;
import jakarta.validation.Validator;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Componentes de consumo ya configurados que usan los listeners: fábrica con ACK manual, reintentos y lectura. */
@Configuration(proxyBeanMethods = false)
public class RabbitListenerConfig {

    /**
     * Fábrica {@code manualAckContainerFactory}: parte de spring.rabbitmq.listener.simple.* y fija explícitamente
     * ACK manual, prefetch, sin requeue por defecto y sin retry interceptor (los reintentos los decide ManualAckHandler).
     */
    @Bean
    SimpleRabbitListenerContainerFactory manualAckContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory, MessagingProperties messaging) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(messaging.consumer().prefetch());
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain();
        return factory;
    }

    /** Tabla de decisiones ACK/NACK con la política de reintentos de messaging.consumer.retry.*. */
    @Bean
    ManualAckHandler manualAckHandler(MessagingProperties messaging) {
        var retry = messaging.consumer().retry();
        return new ManualAckHandler(retry.maxAttempts(), retry.initialInterval(), retry.multiplier(), retry.maxInterval());
    }

    /** Lectura estricta de comandos con el tamaño máximo de messaging.consumer.max-payload-bytes. */
    @Bean
    JsonCommandReader jsonCommandReader(JsonMapper json, Validator validator, MessagingProperties messaging) {
        return new JsonCommandReader(json, validator, messaging.consumer().maxPayloadBytes());
    }

    /** Idempotencia en memoria con capacidad messaging.consumer.idempotency-cache-size. */
    @Bean
    ProcessedMessageRegistry processedMessageRegistry(MessagingProperties messaging) {
        return new ProcessedMessageRegistry(messaging.consumer().idempotencyCacheSize());
    }
}
