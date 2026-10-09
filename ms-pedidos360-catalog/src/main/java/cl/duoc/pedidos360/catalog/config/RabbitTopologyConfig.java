package cl.duoc.pedidos360.catalog.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología que declara catalog para la ruta del ticket de cocina (RabbitAdmin la crea al conectar).
 * Los argumentos son idénticos en valor y tipo a los que declara orders; una diferencia provocaría
 * 406 PRECONDITION_FAILED. Solo existe con {@code catalog.commands.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "catalog.commands.enabled", havingValue = "true")
public class RabbitTopologyConfig {

    /** Exchange direct {@code cmd.direct}: orders publica el ticket con routing key {@code kitchen.ticket}. */
    @Bean
    DirectExchange commandDirectExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().direct()).durable(true).build();
    }

    /** Exchange topic {@code cmd.topic}: variantes del ticket por el patrón {@code kitchen.#}. */
    @Bean
    TopicExchange commandTopicExchange(MessagingProperties messaging) {
        return ExchangeBuilder.topicExchange(messaging.exchanges().topic()).durable(true).build();
    }

    /** Exchange direct {@code cmd.dead.dlx}: destino de los tickets que catalog rechaza (nack sin requeue). */
    @Bean
    DirectExchange deadLetterExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().deadLetter()).durable(true).build();
    }

    /**
     * {@code q.cmd.kitchen}: orders --cmd.direct[kitchen.ticket]--> q.cmd.kitchen --> catalog;
     * si falla --cmd.dead.dlx[kitchen.ticket]--> q.cmd.kitchen.dlq. Llena, rechaza publicaciones.
     */
    @Bean
    Queue kitchenQueue(MessagingProperties messaging) {
        var route = messaging.routes().kitchen();
        return QueueBuilder.durable(route.queue())
                .deadLetterExchange(messaging.exchanges().deadLetter())
                .deadLetterRoutingKey(route.routingKey())
                .maxLength(messaging.queues().maxLength())
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }

    /** {@code q.cmd.kitchen.dlq}: conserva 7 días (máx. 10000) los tickets rechazados para inspección y reproceso. */
    @Bean
    Queue kitchenDeadLetterQueue(MessagingProperties messaging) {
        return QueueBuilder.durable(messaging.routes().kitchen().dlq())
                .ttl(Math.toIntExact(messaging.queues().deadLetterTtl().toMillis()))
                .maxLength(messaging.queues().deadLetterMaxLength())
                .build();
    }

    /** cmd.direct --kitchen.ticket--> q.cmd.kitchen: ticket al aceptar un pedido. */
    @Bean
    Binding kitchenDirectBinding(@Qualifier("kitchenQueue") Queue queue,
            @Qualifier("commandDirectExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().kitchen().routingKey());
    }

    /** cmd.topic --kitchen.#--> q.cmd.kitchen: variantes publicadas por el exchange topic. */
    @Bean
    Binding kitchenTopicBinding(@Qualifier("kitchenQueue") Queue queue,
            @Qualifier("commandTopicExchange") TopicExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().kitchen().topicPattern());
    }

    /** cmd.dead.dlx --kitchen.ticket--> q.cmd.kitchen.dlq: tickets inválidos o con reintentos agotados. */
    @Bean
    Binding kitchenDeadLetterBinding(@Qualifier("kitchenDeadLetterQueue") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().kitchen().routingKey());
    }
}
