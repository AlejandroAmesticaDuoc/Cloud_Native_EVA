package cl.duoc.pedidos360.audit.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de auditoría de dead letters. {@code cmd.dead.dlx} es direct: cada rechazo de q.cmd.email,
 * q.cmd.kitchen o q.cmd.invoice llega con su routing key a la DLQ de la ruta y, por un binding extra,
 * también a {@code q.audit.dead-letters}. Así la DLQ conserva el mensaje para reproceso y audit registra una copia.
 * Solo existe con {@code audit.dead-letters.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "audit.dead-letters.enabled", havingValue = "true")
public class RabbitTopologyConfig {

    /** Exchange direct {@code cmd.dead.dlx}: el mismo que declaran orders y los consumidores (durable, sin argumentos). */
    @Bean
    DirectExchange deadLetterExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().deadLetter()).durable(true).build();
    }

    /**
     * {@code q.audit.dead-letters}: cmd.dead.dlx[email.send|kitchen.ticket|invoice.gen] --> q.audit.dead-letters --> audit;
     * si audit lo rechaza --cmd.dead.dlx[audit.dead-letters]--> q.audit.dead-letters.dlq. Máximo 10000 mensajes.
     */
    @Bean
    Queue auditDeadLetterQueue(MessagingProperties messaging) {
        var deadLetters = messaging.deadLetters();
        return QueueBuilder.durable(deadLetters.queue())
                .deadLetterExchange(messaging.exchanges().deadLetter())
                .deadLetterRoutingKey(deadLetters.routingKey())
                .maxLength(messaging.queues().maxLength())
                .build();
    }

    /** {@code q.audit.dead-letters.dlq}: copias que audit no pudo registrar; retención 7 días y máx. 10000. */
    @Bean
    Queue auditDeadLetterDlq(MessagingProperties messaging) {
        return QueueBuilder.durable(messaging.deadLetters().dlq())
                .ttl(Math.toIntExact(messaging.queues().deadLetterTtl().toMillis()))
                .maxLength(messaging.queues().deadLetterMaxLength())
                .build();
    }

    /**
     * Un binding por ruta de comandos (messaging.dead-letters.source-routing-keys):
     * cmd.dead.dlx --email.send / kitchen.ticket / invoice.gen--> q.audit.dead-letters (copia para auditoría).
     */
    @Bean
    Declarables auditSourceBindings(@Qualifier("auditDeadLetterQueue") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return new Declarables(messaging.deadLetters().sourceRoutingKeys().stream()
                .map(routingKey -> BindingBuilder.bind(queue).to(exchange).with(routingKey)).toList());
    }

    /** cmd.dead.dlx --audit.dead-letters--> q.audit.dead-letters.dlq: rechazos de la propia auditoría. */
    @Bean
    Binding auditDeadLetterDlqBinding(@Qualifier("auditDeadLetterDlq") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.deadLetters().routingKey());
    }
}
