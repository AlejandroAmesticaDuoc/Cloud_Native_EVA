package cl.duoc.pedidos360.audit.messaging;

import cl.duoc.pedidos360.audit.service.AuditStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {
    private final AuditStore store;

    public OrderEventListener(AuditStore store) {
        this.store = store;
    }

    @KafkaListener(id = "audit-orders", idIsGroup = false, topics = "${audit.events.topic}",
            autoStartup = "${audit.events.enabled}")
    public void receive(ConsumerRecord<String, String> record) {
        store.receive(record);
    }
}
