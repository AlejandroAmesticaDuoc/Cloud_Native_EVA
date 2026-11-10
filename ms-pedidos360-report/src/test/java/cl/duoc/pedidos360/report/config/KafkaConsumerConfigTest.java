package cl.duoc.pedidos360.report.config;

import static org.junit.jupiter.api.Assertions.*;
import cl.duoc.pedidos360.report.exception.InvalidEventException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.BackOffExecution;

class KafkaConsumerConfigTest {
    @Test
    void transientFailuresBackOffExponentiallyWithoutAttemptLimit() {
        var backOff = KafkaConsumerConfig.backOffFor(
                new ListenerExecutionFailedException("l", new DataAccessResourceFailureException("db")), 1000, 30000);
        assertNotNull(backOff);
        var execution = backOff.start();
        long previous = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            long next = execution.nextBackOff();
            assertNotEquals(BackOffExecution.STOP, next, "nunca se rinde con la base caída");
            assertTrue(next >= previous && next <= 30000);
            previous = next;
        }
        assertEquals(30000, previous);
    }

    @Test
    void otherFailuresUseTheDefaultLimitedBackOff() {
        assertNull(KafkaConsumerConfig.backOffFor(new IllegalStateException("boom"), 1000, 30000));
        assertNull(KafkaConsumerConfig.backOffFor(new InvalidEventException("EVENT_CONFLICT", "x"), 1000, 30000));
    }
}
