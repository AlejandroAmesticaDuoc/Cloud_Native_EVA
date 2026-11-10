package cl.duoc.pedidos360.report.messaging;

import cl.duoc.pedidos360.report.dto.OrderEvent;
import cl.duoc.pedidos360.report.service.ReportProjection;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de orders.events (grupo REPORT_KAFKA_GROUP): alimenta la proyección de pedidos para los KPIs.
 * Recibe el DTO ya validado y delega en ReportProjection; los errores los decide KafkaConsumerConfig.
 */
@Component
public class OrderEventListener {
    private final ReportProjection projection;

    public OrderEventListener(ReportProjection projection) {
        this.projection = projection;
    }

    @KafkaListener(id = "report-orders", idIsGroup = false, topics = "${report.events.topic}",
            autoStartup = "${report.events.enabled}")
    public void receive(ConsumerRecord<String, OrderEvent> record) {
        projection.record(record.key(), record.value());
    }
}
