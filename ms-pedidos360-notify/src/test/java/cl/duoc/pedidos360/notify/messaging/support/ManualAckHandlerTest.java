package cl.duoc.pedidos360.notify.messaging.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.mail.MailSendException;

/** Tabla de decisiones ACK/NACK con un Channel simulado. */
class ManualAckHandlerTest {
    static final long TAG = 42L;
    Channel channel = mock(Channel.class);
    ManualAckHandler handler = new ManualAckHandler(3, Duration.ofMillis(5), 2, Duration.ofMillis(20));
    Message message = MessageBuilder.withBody("{}".getBytes()).setMessageId("m-1").setType("email.send")
            .setCorrelationId("order-1").setHeader("x-trace-id", "trace\nforjado").build();

    {
        message.getMessageProperties().setConsumerQueue("q.cmd.email");
    }

    @AfterEach
    void neverRequeues() throws Exception {
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void acknowledgesProcessedMessages() throws Exception {
        handler.handle(message, channel, TAG, received -> ManualAckHandler.Outcome.PROCESSED);
        verify(channel).basicAck(TAG, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void acknowledgesDuplicatesWithoutRetrying() throws Exception {
        var calls = new AtomicInteger();
        handler.handle(message, channel, TAG, received -> {
            calls.incrementAndGet();
            return ManualAckHandler.Outcome.DUPLICATE;
        });
        assertEquals(1, calls.get());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void rejectsInvalidMessagesImmediatelyWithoutRetries() throws Exception {
        var calls = new AtomicInteger();
        handler.handle(message, channel, TAG, received -> {
            calls.incrementAndGet();
            throw new InvalidMessageException("JSON inválido");
        });
        assertEquals(1, calls.get());
        verify(channel).basicNack(TAG, false, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void retriesTransientErrorsAndThenRejectsToTheDeadLetterQueue() throws Exception {
        var calls = new AtomicInteger();
        handler.handle(message, channel, TAG, received -> {
            calls.incrementAndGet();
            throw new MailSendException("SMTP caído");
        });
        assertEquals(3, calls.get());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void acknowledgesWhenATransientErrorRecovers() throws Exception {
        var calls = new AtomicInteger();
        handler.handle(message, channel, TAG, received -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("base no disponible");
            return ManualAckHandler.Outcome.PROCESSED;
        });
        assertEquals(3, calls.get());
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void waitsWithExponentialBackoffBetweenAttempts() {
        var slow = new ManualAckHandler(3, Duration.ofMillis(60), 2, Duration.ofMillis(100));
        long start = System.nanoTime();
        slow.handle(message, channel, TAG, received -> { throw new IllegalStateException("caído"); });
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(elapsed >= 160, "esperas 60 ms + 100 ms (tope), fue " + elapsed);
    }

    @Test
    void doesNotLetChannelFailuresEscape() throws Exception {
        doThrow(new IOException("canal cerrado")).when(channel).basicAck(anyLong(), anyBoolean());
        doThrow(new IOException("canal cerrado")).when(channel).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertDoesNotThrow(() -> handler.handle(message, channel, TAG, received -> ManualAckHandler.Outcome.PROCESSED));
        assertDoesNotThrow(() -> handler.handle(message, channel, TAG, received -> {
            throw new InvalidMessageException("veneno");
        }));
    }

    @Test
    void rejectsInsteadOfRetryingWhenInterrupted() throws Exception {
        Thread.currentThread().interrupt();
        try {
            handler.handle(message, channel, TAG, received -> { throw new IllegalStateException("caído"); });
            verify(channel).basicNack(TAG, false, false);
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void validatesThePolicy() {
        assertThrows(IllegalArgumentException.class, () -> new ManualAckHandler(0, Duration.ZERO, 2, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ManualAckHandler(3, Duration.ofSeconds(2), 2, Duration.ofSeconds(1)));
    }

    @Test
    void registryForgetsTheLeastRecentlyUsedEventIds() {
        var registry = new ProcessedMessageRegistry(2);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        registry.markProcessed(first);
        registry.markProcessed(second);
        assertTrue(registry.isProcessed(first));
        registry.markProcessed(UUID.randomUUID());
        assertTrue(registry.isProcessed(first));
        assertFalse(registry.isProcessed(second));
        assertEquals(2, registry.size());
    }
}
