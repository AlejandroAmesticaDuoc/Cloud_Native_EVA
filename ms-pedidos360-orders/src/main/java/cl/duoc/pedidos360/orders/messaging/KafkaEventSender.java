package cl.duoc.pedidos360.orders.messaging;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orders.events.enabled", havingValue = "true")
public class KafkaEventSender {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaEventSender(KafkaTemplate<String, String> kafka, @Value("${orders.events.topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    public void send(long orderId, String payload) throws Exception {
        kafka.send(topic, Long.toString(orderId), payload).get(10, TimeUnit.SECONDS);
    }
}
