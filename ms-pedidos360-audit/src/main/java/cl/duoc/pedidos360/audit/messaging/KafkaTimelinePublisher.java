package cl.duoc.pedidos360.audit.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import cl.duoc.pedidos360.audit.dto.AuditTimelineEntry;
import cl.duoc.pedidos360.audit.exception.TimelinePublishException;
import cl.duoc.pedidos360.audit.service.TimelinePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Productor del tópico audit.timeline: publica {@link AuditTimelineEntry} en JSON con el id del pedido como clave
 * (misma partición por pedido) y espera la confirmación del broker (acks=all).
 */
@Component
@ConditionalOnProperty(name = "audit.timeline.enabled", havingValue = "true")
public class KafkaTimelinePublisher implements TimelinePublisher {
    private final KafkaTemplate<String, Object> kafka;
    private final String topic;

    public KafkaTimelinePublisher(KafkaTemplate<String, Object> kafka, @Value("${audit.timeline.topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public void publish(AuditTimelineEntry entry) {
        try {
            kafka.send(topic, Long.toString(entry.orderId()), entry).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TimelinePublishException("Publicación interrumpida en " + topic, exception);
        } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException exception) {
            throw new TimelinePublishException("Kafka no confirmó la entrada " + entry.entryId() + " en " + topic, exception);
        }
    }
}
