package cl.duoc.pedidos360.orders.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orders.notifications.enabled", havingValue = "true")
public class RabbitEmailSender {
    private final RabbitTemplate rabbit;
    private final String queue;

    public RabbitEmailSender(RabbitTemplate rabbit,
            @Value("${messaging.email.queue}") String queue) {
        this.rabbit = rabbit;
        this.queue = queue;
    }

    public void send(String eventId, String payload) throws Exception {
        var message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setMessageId(eventId).build();
        var correlation = new CorrelationData();
        rabbit.send("", queue, message, correlation);
        var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirmation.ack() || correlation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ no confirmó la entrega a la cola");
        }
    }
}
