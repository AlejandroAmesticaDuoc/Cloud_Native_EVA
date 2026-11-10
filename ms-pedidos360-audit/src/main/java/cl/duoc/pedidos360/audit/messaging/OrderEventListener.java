package cl.duoc.pedidos360.audit.messaging;

import cl.duoc.pedidos360.audit.dto.OrderEvent;
import cl.duoc.pedidos360.audit.service.AuditStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de orders.events (grupo AUDIT_KAFKA_GROUP). Recibe el DTO ya deserializado y validado, y delega en
 * AuditStore; los errores los decide el DefaultErrorHandler de KafkaConsumerConfig (reintentos, rechazo y DLT).
 */
@Component
public class OrderEventListener {
    private final AuditStore store;

    public OrderEventListener(AuditStore store) {
        this.store = store;
    }

    @KafkaListener(id = "audit-orders", idIsGroup = false, topics = "${audit.events.topic}",
            autoStartup = "${audit.events.enabled}")
    public void receive(ConsumerRecord<String, OrderEvent> record) {
        store.record(record.key(), record.value());
    }
}
