package cl.duoc.pedidos360.orders.config;

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
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topología RabbitMQ que declara orders como productor de los tres comandos (RabbitAdmin la crea al conectar).
 * Orders declara todas las colas para que un comando no se pierda aunque el consumidor aún no haya arrancado.
 * Los argumentos de cola deben ser idénticos en valor y tipo a los de notify, catalog y report.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "orders.notifications.enabled", havingValue = "true")
public class RabbitTopologyConfig {

    /** Exchange direct {@code cmd.direct}: entrega cada comando a la cola cuya routing key coincide exactamente. */
    @Bean
    DirectExchange commandDirectExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().direct()).durable(true).build();
    }

    /** Exchange topic {@code cmd.topic}: enruta por patrón; orders lo usa para el correo prioritario {@code email.send.high}. */
    @Bean
    TopicExchange commandTopicExchange(MessagingProperties messaging) {
        return ExchangeBuilder.topicExchange(messaging.exchanges().topic()).durable(true).build();
    }

    /** Exchange direct {@code cmd.dead.dlx}: recibe los mensajes rechazados (nack sin requeue) y los lleva a su DLQ. */
    @Bean
    DirectExchange deadLetterExchange(MessagingProperties messaging) {
        return ExchangeBuilder.directExchange(messaging.exchanges().deadLetter()).durable(true).build();
    }

    /** {@code q.cmd.email}: orders --cmd.direct[email.send] o cmd.topic[email.#]--> q.cmd.email --> notify. */
    @Bean
    Queue emailQueue(MessagingProperties messaging) {
        return commandQueue(messaging, messaging.routes().email());
    }

    /** {@code q.cmd.email.dlq}: si notify rechaza --cmd.dead.dlx[email.send]--> q.cmd.email.dlq (retención 7 días). */
    @Bean
    Queue emailDeadLetterQueue(MessagingProperties messaging) {
        return deadLetterQueue(messaging, messaging.routes().email());
    }

    /** {@code q.cmd.kitchen}: orders --cmd.direct[kitchen.ticket]--> q.cmd.kitchen --> catalog (ticket de cocina). */
    @Bean
    Queue kitchenQueue(MessagingProperties messaging) {
        return commandQueue(messaging, messaging.routes().kitchen());
    }

    /** {@code q.cmd.kitchen.dlq}: si catalog rechaza --cmd.dead.dlx[kitchen.ticket]--> q.cmd.kitchen.dlq. */
    @Bean
    Queue kitchenDeadLetterQueue(MessagingProperties messaging) {
        return deadLetterQueue(messaging, messaging.routes().kitchen());
    }

    /** {@code q.cmd.invoice}: orders --cmd.direct[invoice.gen]--> q.cmd.invoice --> report (boleta). */
    @Bean
    Queue invoiceQueue(MessagingProperties messaging) {
        return commandQueue(messaging, messaging.routes().invoice());
    }

    /** {@code q.cmd.invoice.dlq}: si report rechaza --cmd.dead.dlx[invoice.gen]--> q.cmd.invoice.dlq. */
    @Bean
    Queue invoiceDeadLetterQueue(MessagingProperties messaging) {
        return deadLetterQueue(messaging, messaging.routes().invoice());
    }

    /** cmd.direct --email.send--> q.cmd.email: correo de creación y cambios de estado. */
    @Bean
    Binding emailDirectBinding(@Qualifier("emailQueue") Queue queue,
            @Qualifier("commandDirectExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().email().routingKey());
    }

    /** cmd.topic --email.#--> q.cmd.email: incluye {@code email.send.high} (correo prioritario por cancelación). */
    @Bean
    Binding emailTopicBinding(@Qualifier("emailQueue") Queue queue,
            @Qualifier("commandTopicExchange") TopicExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().email().topicPattern());
    }

    /** cmd.dead.dlx --email.send--> q.cmd.email.dlq: correos rechazados por notify. */
    @Bean
    Binding emailDeadLetterBinding(@Qualifier("emailDeadLetterQueue") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().email().routingKey());
    }

    /** cmd.direct --kitchen.ticket--> q.cmd.kitchen: ticket de cocina al aceptar un pedido. */
    @Bean
    Binding kitchenDirectBinding(@Qualifier("kitchenQueue") Queue queue,
            @Qualifier("commandDirectExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().kitchen().routingKey());
    }

    /** cmd.topic --kitchen.#--> q.cmd.kitchen: permite publicar variantes del ticket por el exchange topic. */
    @Bean
    Binding kitchenTopicBinding(@Qualifier("kitchenQueue") Queue queue,
            @Qualifier("commandTopicExchange") TopicExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().kitchen().topicPattern());
    }

    /** cmd.dead.dlx --kitchen.ticket--> q.cmd.kitchen.dlq: tickets rechazados por catalog. */
    @Bean
    Binding kitchenDeadLetterBinding(@Qualifier("kitchenDeadLetterQueue") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().kitchen().routingKey());
    }

    /** cmd.direct --invoice.gen--> q.cmd.invoice: boleta al entregar un pedido. */
    @Bean
    Binding invoiceDirectBinding(@Qualifier("invoiceQueue") Queue queue,
            @Qualifier("commandDirectExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().invoice().routingKey());
    }

    /** cmd.topic --invoice.#--> q.cmd.invoice: permite publicar variantes de la boleta por el exchange topic. */
    @Bean
    Binding invoiceTopicBinding(@Qualifier("invoiceQueue") Queue queue,
            @Qualifier("commandTopicExchange") TopicExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().invoice().topicPattern());
    }

    /** cmd.dead.dlx --invoice.gen--> q.cmd.invoice.dlq: boletas rechazadas por report. */
    @Bean
    Binding invoiceDeadLetterBinding(@Qualifier("invoiceDeadLetterQueue") Queue queue,
            @Qualifier("deadLetterExchange") DirectExchange exchange, MessagingProperties messaging) {
        return BindingBuilder.bind(queue).to(exchange).with(messaging.routes().invoice().routingKey());
    }

    /** Cola principal: rechazos a cmd.dead.dlx con la routing key de la ruta; llena, rechaza la publicación (nack). */
    private static Queue commandQueue(MessagingProperties messaging, MessagingProperties.CommandRoute route) {
        return QueueBuilder.durable(route.queue())
                .deadLetterExchange(messaging.exchanges().deadLetter())
                .deadLetterRoutingKey(route.routingKey())
                .maxLength(messaging.queues().maxLength())
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }

    /** DLQ: conserva los rechazados para inspección y reproceso (TTL Integer y largo máximo Long). */
    private static Queue deadLetterQueue(MessagingProperties messaging, MessagingProperties.CommandRoute route) {
        return QueueBuilder.durable(route.dlq())
                .ttl(Math.toIntExact(messaging.queues().deadLetterTtl().toMillis()))
                .maxLength(messaging.queues().deadLetterMaxLength())
                .build();
    }
}
