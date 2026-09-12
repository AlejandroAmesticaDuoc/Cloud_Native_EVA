package cl.duoc.pedidos360.orders.config;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "orders.notifications.enabled", havingValue = "true")
public class NotificationConfig {
    @Bean
    Declarables emailQueues(@Value("${messaging.email.queue}") String queue,
            @Value("${messaging.email.dlq}") String dlq) {
        return new Declarables(new Queue(dlq, true), QueueBuilder.durable(queue)
                .deadLetterExchange("").deadLetterRoutingKey(dlq).build());
    }
}
