package cl.duoc.pedidos360.audit.messaging;

import cl.duoc.pedidos360.audit.service.AuditStore;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Destino final de un mensaje de orders.events que Audit no puede procesar: registra el rechazo en
 * audit_rejections (solo hash, sin contenido) y publica el mensaje original en orders.events.audit.DLT
 * (misma partición, con headers de la excepción) para inspeccionarlo o reprocesarlo. El consumidor sigue
 * con el siguiente mensaje. Si la base o Kafka fallan aquí, el error handler vuelve a intentarlo.
 */
@Component
public class RejectedEventRecoverer implements ConsumerAwareRecordRecoverer {
    private static final Logger log = LoggerFactory.getLogger(RejectedEventRecoverer.class);
    private final AuditStore store;
    private final JsonMapper json;
    private final String deadLetterTopic;
    private final DeadLetterPublishingRecoverer deadLetters;

    public RejectedEventRecoverer(AuditStore store, JsonMapper json, DeadLetterPublishingRecoverer deadLetters,
            @Value("${audit.events.dead-letter-topic}") String deadLetterTopic) {
        this.store = store;
        this.json = json;
        this.deadLetters = deadLetters;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        String reason = KafkaFailures.reason(exception);
        byte[] raw = KafkaFailures.rawPayload(exception);
        if (raw == null && record.value() != null) raw = json.writeValueAsBytes(record.value());
        store.reject(record.topic(), record.partition(), record.offset(), reason, raw);
        deadLetters.accept(record, consumer, exception);
        log.warn("[DLT] Evento rechazado topic={} partition={} offset={} key={} motivo={} destino={} causa={}",
                record.topic(), record.partition(), record.offset(), record.key(), reason, deadLetterTopic,
                KafkaFailures.describe(exception));
    }
}
