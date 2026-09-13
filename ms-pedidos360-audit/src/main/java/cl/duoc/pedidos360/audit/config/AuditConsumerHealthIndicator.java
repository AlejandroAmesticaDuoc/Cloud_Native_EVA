package cl.duoc.pedidos360.audit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("auditConsumer")
public class AuditConsumerHealthIndicator implements HealthIndicator {
    private final KafkaListenerEndpointRegistry registry;
    private final boolean enabled;

    public AuditConsumerHealthIndicator(KafkaListenerEndpointRegistry registry,
            @Value("${audit.events.enabled}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        var container = registry.getListenerContainer("audit-orders");
        boolean assigned = container != null && container.isRunning()
                && container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty();
        return (enabled && assigned ? Health.up() : Health.outOfService()).build();
    }
}
