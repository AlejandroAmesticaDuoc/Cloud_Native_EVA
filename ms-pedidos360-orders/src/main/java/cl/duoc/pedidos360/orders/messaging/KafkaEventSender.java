package cl.duoc.pedidos360.orders.messaging;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Productor del tópico orders.events. Publica el DTO {@link OrderEvent} (JSON con JacksonJsonSerializer y sin
 * headers de tipo Java) usando el id del pedido como clave: todos los eventos de un pedido van a la misma
 * partición y se consumen en orden. Espera la confirmación del broker (acks=all + min.insync.replicas=2).
 */
@Component
@ConditionalOnProperty(name = "orders.events.enabled", havingValue = "true")
public class KafkaEventSender {
    private final KafkaTemplate<String, OrderEvent> kafka;
    private final JsonMapper json;
    private final String topic;

    public KafkaEventSender(KafkaTemplate<String, OrderEvent> kafka, JsonMapper json,
            @Value("${orders.events.topic}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    /** Publica un evento guardado en la outbox: convierte el JSON persistido al DTO tipado antes de enviarlo. */
    public void send(long orderId, String payload) throws Exception {
        OrderEvent event = json.readValue(payload, OrderEvent.class);
        if (event.orderId() != orderId) {
            throw new IllegalStateException("El evento de la outbox no corresponde al pedido " + orderId);
        }
        send(event);
    }

    public void send(OrderEvent event) throws Exception {
        kafka.send(topic, Long.toString(event.orderId()), event).get(10, TimeUnit.SECONDS);
    }
}
