package cl.duoc.pedidos360.report.messaging;

import cl.duoc.pedidos360.report.service.ReportProjection;
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
 * Destino final de un mensaje que Report no puede procesar (de orders.events o audit.timeline): registra el
 * rechazo en report_rejections (solo hash) y publica el mensaje original en &lt;tópico&gt;.report.DLT, en la
 * misma partición y con headers de la excepción. El consumidor continúa con el siguiente mensaje.
 */
@Component
public class RejectedEventRecoverer implements ConsumerAwareRecordRecoverer {
    private static final Logger log = LoggerFactory.getLogger(RejectedEventRecoverer.class);
    private final ReportProjection projection;
    private final JsonMapper json;
    private final DeadLetterPublishingRecoverer deadLetters;
    private final String deadLetterSuffix;

    public RejectedEventRecoverer(ReportProjection projection, JsonMapper json, DeadLetterPublishingRecoverer deadLetters,
            @Value("${report.dead-letter-suffix}") String deadLetterSuffix) {
        this.projection = projection;
        this.json = json;
        this.deadLetters = deadLetters;
        this.deadLetterSuffix = deadLetterSuffix;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        String reason = KafkaFailures.reason(exception);
        byte[] raw = KafkaFailures.rawPayload(exception);
        if (raw == null && record.value() != null) raw = json.writeValueAsBytes(record.value());
        projection.reject(record.topic(), record.partition(), record.offset(), reason, raw);
        deadLetters.accept(record, consumer, exception);
        log.warn("[DLT] Mensaje rechazado topic={} partition={} offset={} key={} motivo={} destino={} causa={}",
                record.topic(), record.partition(), record.offset(), record.key(), reason,
                record.topic() + deadLetterSuffix, KafkaFailures.describe(exception));
    }
}
