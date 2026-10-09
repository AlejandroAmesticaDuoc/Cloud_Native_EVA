package cl.duoc.pedidos360.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.notify.dto.EmailCommand;
import cl.duoc.pedidos360.notify.messaging.email.EmailCommandListener;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// Sin broker: el listener se invoca directamente con un Channel simulado para verificar ack/nack.
@SpringBootTest(properties = {
    "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.dynamic=false",
    "RABBITMQ_USERNAME=notify-test", "RABBITMQ_PASSWORD=notify-test-only",
    "management.health.rabbit.enabled=false", "management.health.mail.enabled=false",
    "management.endpoint.health.group.readiness.include=readinessState",
    "messaging.consumer.retry.initial-interval=10ms", "messaging.consumer.retry.max-interval=40ms"
})
@AutoConfigureMockMvc
class NotifyTest {
    static final long TAG = 7L;
    @Autowired EmailCommandListener listener;
    @Autowired JsonMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender sender;
    Channel channel = mock(Channel.class);

    EmailCommand command() {
        return new EmailCommand(1, UUID.randomUUID(), 21L, "cliente-interno",
                EmailCommand.OrderStatus.ACEPTADO, Instant.parse("2026-09-12T00:00:00Z"), "notify-test-1");
    }

    Message message(String payload) {
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8)).setContentType("application/json").build();
    }

    void receive(Message message) {
        assertDoesNotThrow(() -> listener.onMessage(message, channel, TAG));
    }

    void assertSentToDeadLetterQueue() throws Exception {
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @AfterEach
    void neverRequeues() throws Exception {
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void sendsOnlyToConfiguredDemoRecipient() throws Exception {
        var command = command();
        receive(message(json.writeValueAsString(command)));
        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        verify(channel).basicAck(TAG, false);
        assertArrayEquals(new String[]{"demo@pedidos360.test"}, mail.getValue().getTo());
        assertEquals("no-reply@pedidos360.test", mail.getValue().getFrom());
        assertTrue(mail.getValue().getSubject().contains("#21 | ACEPTADO"));
        assertTrue(mail.getValue().getText().contains(command.eventId().toString()));
        assertTrue(mail.getValue().getText().contains("notify-test-1"));
        assertFalse(mail.getValue().getText().contains("cliente-interno"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "eventId", "orderId", "customerId", "status", "occurredAt", "traceId"})
    void rejectsMissingFields(String field) throws Exception {
        var payload = json.valueToTree(command()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) payload).remove(field);
        receive(message(payload.toString()));
        assertSentToDeadLetterQueue();
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "not-json"})
    void rejectsMalformedCommands(String payload) throws Exception {
        receive(message(payload));
        assertSentToDeadLetterQueue();
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"orderId\":0", "\"orderId\":1.5", "\"schemaVersion\":2", "\"status\":\"PAGADO\"",
            "\"traceId\":\"bad trace\"", "\"customerId\":\"\"", "\"recipient\":\"untrusted@example.test\""})
    void rejectsInvalidValuesAndUntrustedRecipient(String field) throws Exception {
        var payload = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command());
        var replacement = (tools.jackson.databind.node.ObjectNode) json.readTree("{" + field + "}");
        payload.setAll(replacement);
        receive(message(payload.toString()));
        assertSentToDeadLetterQueue();
        verifyNoInteractions(sender);
    }

    @Test
    void rejectsLargeBodiesAndOtherContentTypes() throws Exception {
        receive(message("x".repeat(8193)));
        receive(new Message(json.writeValueAsBytes(command())));
        verify(channel, times(2)).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(sender);
    }

    @Test
    void retriesSmtpFailureThenSendsToDeadLetterQueue() throws Exception {
        doThrow(new MailSendException("SMTP no disponible")).when(sender).send(any(SimpleMailMessage.class));
        receive(message(json.writeValueAsString(command())));
        verify(sender, times(3)).send(any(SimpleMailMessage.class));
        assertSentToDeadLetterQueue();
    }

    @Test
    void recoversWhenSmtpFailsOnlyOnce() throws Exception {
        doThrow(new MailSendException("SMTP no disponible")).doNothing().when(sender).send(any(SimpleMailMessage.class));
        receive(message(json.writeValueAsString(command())));
        verify(sender, times(2)).send(any(SimpleMailMessage.class));
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void acknowledgesDuplicatesWithoutSendingTheEmailAgain() throws Exception {
        String payload = json.writeValueAsString(command());
        receive(message(payload));
        receive(message(payload));
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
        verify(channel, times(2)).basicAck(TAG, false);
    }

    @Test
    void rejectsMessageIdDifferentFromEventId() throws Exception {
        var command = command();
        var message = MessageBuilder.withBody(json.writeValueAsBytes(command)).setContentType("application/json")
                .setMessageId(UUID.randomUUID().toString()).build();
        receive(message);
        assertSentToDeadLetterQueue();
        verifyNoInteractions(sender);
        var matching = MessageBuilder.withBody(json.writeValueAsBytes(command)).setContentType("application/json")
                .setMessageId(command.eventId().toString()).setHeader("x-trace-id", "notify-test-1").build();
        receive(matching);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void exposesHealthWithoutExposingMailOrAdminHttpRoutes() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notify")).andExpect(status().isForbidden());
    }
}
