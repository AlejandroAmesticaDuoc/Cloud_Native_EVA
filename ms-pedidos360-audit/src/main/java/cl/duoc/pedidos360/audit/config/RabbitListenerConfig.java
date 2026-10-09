package cl.duoc.pedidos360.audit.config;

import cl.duoc.pedidos360.audit.messaging.deadletter.DeadLetterMapper;
import cl.duoc.pedidos360.audit.messaging.support.ManualAckHandler;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Componentes de consumo ya configurados que usa el listener de auditoría: fábrica con ACK manual y reintentos. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "audit.dead-letters.enabled", havingValue = "true")
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

    /** Extracción de x-death; sobre messaging.consumer.max-payload-bytes solo se guarda el hash del payload. */
    @Bean
    DeadLetterMapper deadLetterMapper(MessagingProperties messaging) {
        return new DeadLetterMapper(messaging.consumer().maxPayloadBytes());
    }
}
