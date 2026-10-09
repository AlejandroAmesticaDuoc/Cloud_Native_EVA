package cl.duoc.pedidos360.mqadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.DeadLetterQueueStatus;
import cl.duoc.pedidos360.mqadmin.exception.BrokerUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DeadLetterMonitorTest {
    private final RabbitAdminService service = mock(RabbitAdminService.class);
    private final DeadLetterMonitor monitor = new DeadLetterMonitor(service);

    @Test
    void warnsOnlyForQueuesOverTheThreshold(CapturedOutput output) {
        when(service.deadLetterQueues()).thenReturn(List.of(
                new DeadLetterQueueStatus("q.cmd.email.dlq", true, 3, 1, true),
                new DeadLetterQueueStatus("q.cmd.kitchen.dlq", true, 0, 1, false)));
        monitor.check();
        assertThat(output).contains("[ALERTA DLQ] cola=q.cmd.email.dlq mensajes=3 umbral=1")
                .doesNotContain("cola=q.cmd.kitchen.dlq mensajes");
    }

    @Test
    void survivesAnUnavailableBroker(CapturedOutput output) {
        when(service.deadLetterQueues()).thenThrow(new BrokerUnavailableException(
                "La API de management de RabbitMQ no está disponible", null));
        assertThatCode(monitor::check).doesNotThrowAnyException();
        assertThat(output).contains("No se pudo revisar las DLQ");
    }
}
