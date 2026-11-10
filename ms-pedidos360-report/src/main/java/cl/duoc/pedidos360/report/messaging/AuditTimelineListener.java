package cl.duoc.pedidos360.report.messaging;

import cl.duoc.pedidos360.report.dto.AuditTimelineEntry;
import cl.duoc.pedidos360.report.service.ActivityMetricsService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de audit.timeline con su propio consumer group (REPORT_TIMELINE_KAFKA_GROUP): las métricas de
 * actividad avanzan con su propio offset, independiente de la proyección de pedidos. El valor se deserializa
 * con AuditTimelineDeserializer (delegado de ErrorHandlingDeserializer, solo para este listener).
 */
@Component
public class AuditTimelineListener {
    private final ActivityMetricsService metrics;

    public AuditTimelineListener(ActivityMetricsService metrics) {
        this.metrics = metrics;
    }

    @KafkaListener(id = "report-timeline", groupId = "${report.timeline.group}", topics = "${report.timeline.topic}",
            autoStartup = "${report.timeline.enabled}",
            properties = "spring.deserializer.value.delegate.class=cl.duoc.pedidos360.report.messaging.AuditTimelineDeserializer")
    public void receive(ConsumerRecord<String, AuditTimelineEntry> record) {
        metrics.record(record.key(), record.value());
    }
}
