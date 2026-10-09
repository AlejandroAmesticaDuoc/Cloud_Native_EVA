package cl.duoc.pedidos360.notify.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología que declara notify para su ruta (RabbitAdmin la crea al conectar). Los argumentos son idénticos
 * en valor y tipo a los que declara orders; una diferencia provocaría 406 PRECONDITION_FAILED.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitTopologyConfig {

    /** Exchange direct {@code cmd.direct}: orders publica aquí los correos con routing key {@code email.send}. */
    @Bean
    DirectExchange commandDirectExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().direct()).durable(true).build();
    }

    /** Exchange topic {@code cmd.topic}: correos prioritarios ({@code email.send.high}) por el patrón {@code email.#}. */
    @Bean
    TopicExchange commandTopicExchange(MessagingProperties messaging) {
        return ExchangeBuilder.topicExchange(messaging.exchanges().topic()).durable(true).build();
    }

    /** Exchange direct {@code cmd.dead.dlx}: destino de los mensajes que notify rechaza (nack sin requeue). */
    @Bean
    DirectExchange deadLetterExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().deadLetter()).durable(true).build();
    }

    /**
     * {@code q.cmd.email}: orders --cmd.direct[email.send]--> q.cmd.email --> notify;
     * si falla --cmd.dead.dlx[email.send]--> q.cmd.email.dlq. Llena, rechaza publicaciones (el productor reintenta).
     */
    @Bean
    Queue emailQueue(MessagingProperties messaging) {
        var route = messaging.routes().email();
        return QueueBuilder.durable(route.queue())
                .deadLetterExchange(messaging.exchanges().deadLetter())
                .deadLetterRoutingKey(route.routingKey())
                .maxLength(messaging.queues().maxLength())
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }

    /** {@code q.cmd.email.dlq}: conserva 7 días (máx. 10000) los correos rechazados para inspección y reproceso. */
    @Bean
    Queue emailDeadLetterQueue(MessagingProperties messaging) {
        return QueueBuilder.durable(messaging.routes().email().dlq())
                .ttl(Math.toIntExact(messaging.queues().deadLetterTtl().toMillis()))
                .maxLength(messaging.queues().deadLetterMaxLength())
                .build();
    }

    /** cmd.direct --email.send--> q.cmd.email: correo de creación y cambios de estado. */
    @Bean
    Binding emailDirectBinding(@Qualifier("emailQueue") Queue queue,
            @Qualifier("commandDirectExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().email().routingKey());
    }

    /** cmd.topic --email.#--> q.cmd.email: correo prioritario por cancelación ({@code email.send.high}). */
    @Bean
    Binding emailTopicBinding(@Qualifier("emailQueue") Queue queue,
            @Qualifier("commandTopicExchange") TopicExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().email().topicPattern());
    }

    /** cmd.dead.dlx --email.send--> q.cmd.email.dlq: correos inválidos o con reintentos agotados. */
    @Bean
    Binding emailDeadLetterBinding(@Qualifier("emailDeadLetterQueue") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().email().routingKey());
    }
}
