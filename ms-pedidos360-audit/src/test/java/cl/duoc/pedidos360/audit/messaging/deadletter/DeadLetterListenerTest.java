package cl.duoc.pedidos360.audit.messaging.deadletter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import cl.duoc.pedidos360.audit.dto.DeadLetter;
import cl.duoc.pedidos360.audit.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.audit.service.DeadLetterStore;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.DataAccessResourceFailureException;

/** Extracción de x-death y decisiones ACK/NACK del listener de auditoría con un Channel simulado. */
class DeadLetterListenerTest {
    static final long TAG = 5L;
    static final String PAYLOAD = "{\"schemaVersion\":1,\"orderId\":42}";
    static final Instant DIED = Instant.parse("2026-10-08T15:31:00Z");
    Channel channel = mock(Channel.class);
    DeadLetterStore store = mock(DeadLetterStore.class);
    DeadLetterListener listener = new DeadLetterListener(new DeadLetterMapper(64),
            new ManualAckHandler(3, Duration.ofMillis(5), 2, Duration.ofMillis(20)), store);

    static Message deadLetter(byte[] body, String messageId) {
        var message = MessageBuilder.withBody(body).setContentType("application/json").setMessageId(messageId)
                .setType("email.send.high").setCorrelationId("order-42").setHeader("x-trace-id", "trace-demo-001")
                .setHeader("x-first-death-queue", "q.cmd.email").setHeader("x-first-death-reason", "rejected")
                .setHeader("x-first-death-exchange", "cmd.topic").setReceivedRoutingKey("email.send").build();
        message.getMessageProperties().setHeader("x-death", List.of(Map.of("queue", "q.cmd.email",
                "reason", "rejected", "count", 2L, "exchange", "cmd.topic", "routing-keys", List.of("email.send.high"),
                "time", Date.from(DIED))));
        return message;
    }

    DeadLetter recorded() {
        var captor = ArgumentCaptor.forClass(DeadLetter.class);
        verify(store).record(captor.capture());
        return captor.getValue();
    }

    @AfterEach
    void neverRequeues() throws Exception {
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void extractsTheOriginalRouteFromXDeathAndAcknowledges() throws Exception {
        when(store.record(any())).thenReturn(true);
        listener.onMessage(deadLetter(PAYLOAD.getBytes(StandardCharsets.UTF_8), "event-1"), channel, TAG);
        var dead = recorded();
        assertEquals("event-1", dead.messageId());
        assertEquals("q.cmd.email", dead.sourceQueue());
        assertEquals("rejected", dead.reason());
        assertEquals(2, dead.deathCount());
        assertEquals("cmd.topic", dead.originalExchange());
        assertEquals("email.send.high", dead.originalRoutingKey());
        assertEquals("email.send.high", dead.messageType());
        assertEquals("order-42", dead.correlationId());
        assertEquals("trace-demo-001", dead.traceId());
        assertEquals(PAYLOAD, dead.payload());
        assertEquals(PAYLOAD.length(), dead.payloadBytes());
        assertEquals(64, dead.payloadSha256().length());
        assertEquals(DIED, dead.firstDeathAt());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void usesFirstDeathHeadersWhenXDeathIsMissing() throws Exception {
        when(store.record(any())).thenReturn(true);
        var message = deadLetter(PAYLOAD.getBytes(StandardCharsets.UTF_8), "event-2");
        message.getMessageProperties().getHeaders().remove("x-death");
        listener.onMessage(message, channel, TAG);
        var dead = recorded();
        assertEquals("q.cmd.email", dead.sourceQueue());
        assertEquals("rejected", dead.reason());
        assertEquals("cmd.topic", dead.originalExchange());
        assertEquals("email.send", dead.originalRoutingKey());
        assertEquals(1, dead.deathCount());
        assertNull(dead.firstDeathAt());
    }

    @Test
    void keepsOnlyTheHashOfOversizedOrBinaryPayloadsAndHashesMissingMessageIds() throws Exception {
        when(store.record(any())).thenReturn(true);
        listener.onMessage(deadLetter("x".repeat(65).getBytes(StandardCharsets.UTF_8), null), channel, TAG);
        listener.onMessage(deadLetter(new byte[] {(byte) 0xC3, (byte) 0x28}, "binary"), channel, TAG);
        var captor = ArgumentCaptor.forClass(DeadLetter.class);
        verify(store, times(2)).record(captor.capture());
        var oversized = captor.getAllValues().getFirst();
        assertNull(oversized.payload());
        assertEquals(65, oversized.payloadBytes());
        assertEquals("sha256:" + oversized.payloadSha256(), oversized.messageId());
        assertNull(captor.getAllValues().getLast().payload());
        verify(channel, times(2)).basicAck(TAG, false);
    }

    @Test
    void sanitizesHeadersSentByProducers() throws Exception {
        when(store.record(any())).thenReturn(true);
        var message = deadLetter(PAYLOAD.getBytes(StandardCharsets.UTF_8), "event-3");
        message.getMessageProperties().setHeader("x-trace-id", "trace\nforjado" + "x".repeat(200));
        listener.onMessage(message, channel, TAG);
        var dead = recorded();
        assertFalse(dead.traceId().contains("\n"));
        assertEquals(100, dead.traceId().length());
    }

    @Test
    void acknowledgesDeadLettersAlreadyRecorded() throws Exception {
        when(store.record(any())).thenReturn(false);
        listener.onMessage(deadLetter(PAYLOAD.getBytes(StandardCharsets.UTF_8), "event-1"), channel, TAG);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void rejectsMessagesThatAreNotDeadLettersToTheAuditDeadLetterQueue() throws Exception {
        var message = MessageBuilder.withBody(PAYLOAD.getBytes(StandardCharsets.UTF_8)).setMessageId("direct").build();
        listener.onMessage(message, channel, TAG);
        verify(channel).basicNack(TAG, false, false);
        verifyNoInteractions(store);
    }

    @Test
    void retriesDatabaseFailuresAndThenDeadLetters() throws Exception {
        when(store.record(any())).thenThrow(new DataAccessResourceFailureException("base caída"));
        listener.onMessage(deadLetter(PAYLOAD.getBytes(StandardCharsets.UTF_8), "event-1"), channel, TAG);
        verify(store, times(3)).record(any());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
