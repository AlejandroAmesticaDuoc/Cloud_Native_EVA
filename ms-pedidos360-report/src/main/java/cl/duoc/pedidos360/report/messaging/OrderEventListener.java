package cl.duoc.pedidos360.report.messaging;

import cl.duoc.pedidos360.report.service.ReportProjection;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {
    private final ReportProjection projection;

    public OrderEventListener(ReportProjection projection) {
        this.projection = projection;
    }

    @KafkaListener(id = "report-orders", idIsGroup = false, topics = "${report.events.topic}",
            autoStartup = "${report.events.enabled}")
    public void receive(ConsumerRecord<String, String> record) {
        projection.receive(record);
    }
}
