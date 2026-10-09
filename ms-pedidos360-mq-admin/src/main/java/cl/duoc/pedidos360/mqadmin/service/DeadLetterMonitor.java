package cl.duoc.pedidos360.mqadmin.service;

import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.DeadLetterQueueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Revisa periódicamente ({@code mqadmin.alerts.interval}) la profundidad de cada DLQ configurada y
 * escribe una alerta WARN cuando alcanza o supera {@code mqadmin.alerts.threshold}. Los mensajes
 * no se mueven ni se borran: quedan retenidos (TTL de 7 días) para inspección y replay.
 */
@Component
public class DeadLetterMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterMonitor.class);

    private final RabbitAdminService service;

    public DeadLetterMonitor(RabbitAdminService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${mqadmin.alerts.interval}", fixedDelayString = "${mqadmin.alerts.interval}")
    public void check() {
        List<DeadLetterQueueStatus> statuses;
        try {
            statuses = service.deadLetterQueues();
        } catch (RuntimeException unavailable) {
            LOG.warn("[ALERTA DLQ] No se pudo revisar las DLQ: {}", unavailable.getMessage());
            return;
        }
        for (DeadLetterQueueStatus status : statuses) {
            if (status.alert()) {
                LOG.warn("[ALERTA DLQ] cola={} mensajes={} umbral={}", status.queue(), status.messages(), status.threshold());
            } else {
                LOG.debug("DLQ revisada: cola={} existe={} mensajes={}", status.queue(), status.exists(), status.messages());
            }
        }
    }
}
