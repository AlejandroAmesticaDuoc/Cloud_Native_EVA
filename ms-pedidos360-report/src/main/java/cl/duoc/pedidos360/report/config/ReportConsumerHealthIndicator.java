package cl.duoc.pedidos360.report.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("reportConsumer")
public class ReportConsumerHealthIndicator implements HealthIndicator {
    private final KafkaListenerEndpointRegistry registry;
    private final boolean enabled;

    public ReportConsumerHealthIndicator(KafkaListenerEndpointRegistry registry,
            @Value("${report.events.enabled}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        var container = registry.getListenerContainer("report-orders");
        boolean assigned = container != null && container.isRunning()
                && container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty();
        return (enabled && assigned ? Health.up() : Health.outOfService()).build();
    }
}
