package cl.duoc.pedidos360.report.config;

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
 * Topología que declara report para la ruta de boletas (RabbitAdmin la crea al conectar).
 * Los argumentos son idénticos en valor y tipo a los que declara orders; una diferencia provocaría
 * 406 PRECONDITION_FAILED. Solo existe con {@code report.commands.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "report.commands.enabled", havingValue = "true")
public class RabbitTopologyConfig {

    /** Exchange direct {@code cmd.direct}: orders publica la boleta con routing key {@code invoice.gen}. */
    @Bean
    DirectExchange commandDirectExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().direct()).durable(true).build();
    }

    /** Exchange topic {@code cmd.topic}: variantes de la boleta por el patrón {@code invoice.#}. */
    @Bean
    TopicExchange commandTopicExchange(MessagingProperties messaging) {
        return ExchangeBuilder.topicExchange(messaging.exchanges().topic()).durable(true).build();
    }

    /** Exchange direct {@code cmd.dead.dlx}: destino de las boletas que report rechaza (nack sin requeue). */
    @Bean
    DirectExchange deadLetterExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().deadLetter()).durable(true).build();
    }

    /**
     * {@code q.cmd.invoice}: orders --cmd.direct[invoice.gen]--> q.cmd.invoice --> report;
     * si falla --cmd.dead.dlx[invoice.gen]--> q.cmd.invoice.dlq. Llena, rechaza publicaciones.
     */
    @Bean
    Queue invoiceQueue(MessagingProperties messaging) {
        var route = messaging.routes().invoice();
        return QueueBuilder.durable(route.queue())
                .deadLetterExchange(messaging.exchanges().deadLetter())
                .deadLetterRoutingKey(route.routingKey())
                .maxLength(messaging.queues().maxLength())
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }

    /** {@code q.cmd.invoice.dlq}: conserva 7 días (máx. 10000) las boletas rechazadas para inspección y reproceso. */
    @Bean
    Queue invoiceDeadLetterQueue(MessagingProperties messaging) {
        return QueueBuilder.durable(messaging.routes().invoice().dlq())
                .ttl(Math.toIntExact(messaging.queues().deadLetterTtl().toMillis()))
                .maxLength(messaging.queues().deadLetterMaxLength())
                .build();
    }

    /** cmd.direct --invoice.gen--> q.cmd.invoice: boleta al entregar un pedido. */
    @Bean
    Binding invoiceDirectBinding(@Qualifier("invoiceQueue") Queue queue,
            @Qualifier("commandDirectExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().invoice().routingKey());
    }

    /** cmd.topic --invoice.#--> q.cmd.invoice: variantes publicadas por el exchange topic. */
    @Bean
    Binding invoiceTopicBinding(@Qualifier("invoiceQueue") Queue queue,
            @Qualifier("commandTopicExchange") TopicExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().invoice().topicPattern());
    }

    /** cmd.dead.dlx --invoice.gen--> q.cmd.invoice.dlq: boletas inválidas, que no cuadran o con reintentos agotados. */
    @Bean
    Binding invoiceDeadLetterBinding(@Qualifier("invoiceDeadLetterQueue") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().invoice().routingKey());
    }
}
