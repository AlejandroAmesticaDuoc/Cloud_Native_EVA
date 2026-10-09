package cl.duoc.pedidos360.audit.messaging.deadletter;

import cl.duoc.pedidos360.audit.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.audit.service.DeadLetterStore;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del dominio auditoría: q.audit.dead-letters (copia de cada rechazo de los comandos) --> DeadLetterStore.
 * ACK manual según ManualAckHandler: sin x-death -> DLQ de auditoría; error de base -> reintentos y luego DLQ;
 * dead letter ya registrada -> ack. La DLQ original conserva el mensaje para reproceso desde mq-admin.
 */
@Component
@ConditionalOnProperty(name = "audit.dead-letters.enabled", havingValue = "true")
public class DeadLetterListener {
    private final DeadLetterMapper mapper;
    private final ManualAckHandler acks;
    private final DeadLetterStore store;

    public DeadLetterListener(DeadLetterMapper mapper, ManualAckHandler acks, DeadLetterStore store) {
        this.mapper = mapper;
        this.acks = acks;
        this.store = store;
    }

    @RabbitListener(id = "audit-dead-letters", queues = "${messaging.dead-letters.queue}",
            containerFactory = "manualAckContainerFactory")
    public void onMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        acks.handle(message, channel, deliveryTag, received -> store.record(mapper.map(received))
                ? ManualAckHandler.Outcome.PROCESSED : ManualAckHandler.Outcome.DUPLICATE);
    }
}
