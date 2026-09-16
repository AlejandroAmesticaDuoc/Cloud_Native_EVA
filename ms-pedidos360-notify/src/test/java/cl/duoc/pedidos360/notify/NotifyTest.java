package cl.duoc.pedidos360.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.notify.dto.EmailCommand;
import cl.duoc.pedidos360.notify.messaging.EmailCommandListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
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

@SpringBootTest(properties = {
    "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.dynamic=false",
    "RABBITMQ_USERNAME=notify-test", "RABBITMQ_PASSWORD=notify-test-only",
    "management.health.rabbit.enabled=false", "management.health.mail.enabled=false",
    "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
class NotifyTest {
    @Autowired EmailCommandListener listener;
    @Autowired JsonMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender sender;

    EmailCommand command() {
        return new EmailCommand(1, UUID.randomUUID(), 21L, "cliente-interno",
                EmailCommand.OrderStatus.ACEPTADO, Instant.parse("2026-09-12T00:00:00Z"), "notify-test-1");
    }

    Message message(String payload) {
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8)).setContentType("application/json").build();
    }

    @Test
    void sendsOnlyToConfiguredDemoRecipient() {
        var command = command();
        listener.receive(message(json.writeValueAsString(command)));
        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertArrayEquals(new String[]{"demo@pedidos360.test"}, mail.getValue().getTo());
        assertEquals("no-reply@pedidos360.test", mail.getValue().getFrom());
        assertTrue(mail.getValue().getSubject().contains("#21 | ACEPTADO"));
        assertTrue(mail.getValue().getText().contains(command.eventId().toString()));
        assertTrue(mail.getValue().getText().contains("notify-test-1"));
        assertFalse(mail.getValue().getText().contains("cliente-interno"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "eventId", "orderId", "customerId", "status", "occurredAt", "traceId"})
    void rejectsMissingFields(String field) {
        var payload = json.valueToTree(command()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) payload).remove(field);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.receive(message(payload.toString())));
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "not-json"})
    void rejectsMalformedCommands(String payload) {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.receive(message(payload)));
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"orderId\":0", "\"orderId\":1.5", "\"schemaVersion\":2", "\"status\":\"PAGADO\"",
            "\"traceId\":\"bad trace\"", "\"customerId\":\"\"", "\"recipient\":\"untrusted@example.test\""})
    void rejectsInvalidValuesAndUntrustedRecipient(String field) {
        var payload = (tools.jackson.databind.node.ObjectNode) json.valueToTree(command());
        var replacement = (tools.jackson.databind.node.ObjectNode) json.readTree("{" + field + "}");
        payload.setAll(replacement);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.receive(message(payload.toString())));
        verifyNoInteractions(sender);
    }

    @Test
    void rejectsLargeBodiesAndOtherContentTypes() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.receive(message("x".repeat(8193))));
        var raw = new Message(json.writeValueAsBytes(command()));
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.receive(raw));
        verifyNoInteractions(sender);
    }

    @Test
    void propagatesSmtpFailureForListenerRetry() {
        doThrow(new MailSendException("SMTP no disponible")).when(sender).send(any(SimpleMailMessage.class));
        assertThrows(MailSendException.class, () -> listener.receive(message(json.writeValueAsString(command()))));
        verify(sender).send(any(SimpleMailMessage.class));
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
