package cl.duoc.pedidos360.report.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import cl.duoc.pedidos360.report.dto.AuditTimelineEntry;
import cl.duoc.pedidos360.report.dto.OrderEvent;
import cl.duoc.pedidos360.report.service.ReportProjection;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import tools.jackson.databind.json.JsonMapper;

class KafkaPipelineTest {
    static final String ENTRY = """
            {"schemaVersion":1,"entryId":"0b2f6a55-3f0f-4f61-b3a8-7e7d64d0f0a1",
            "sourceEventId":"656efca0-3b3a-423f-a8a0-7a1c21e89d6e","orderId":15,"actorId":"operador",
            "action":"OrderAccepted","previousStatus":"CREADO","status":"ACEPTADO",
            "occurredAt":"2026-11-10T12:00:00Z","recordedAt":"2026-11-10T12:00:01Z",
            "traceId":"pedido-15","origin":"ms-pedidos360-orders"}
            """;
    final AuditTimelineDeserializer timeline = new AuditTimelineDeserializer();

    @Test
    void deserializesTheTimelineContractIntoTheTypedDto() {
        AuditTimelineEntry entry = timeline.deserialize("audit.timeline", ENTRY.getBytes(StandardCharsets.UTF_8));
        assertEquals(15L, entry.orderId());
        assertEquals(OrderEvent.EventType.OrderAccepted, entry.action());
        assertEquals(OrderEvent.Status.CREADO, entry.previousStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "missing", "version", "string-number", "trace", "duplicate", "trailing", "empty"})
    void rejectsAnInvalidTimelineEntry(String scenario) {
        String payload = switch (scenario) {
            case "unknown" -> ENTRY.replace("\"origin\"", "\"token\":\"x\",\"origin\"");
            case "missing" -> ENTRY.replace("\"actorId\":\"operador\",", "");
            case "version" -> ENTRY.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            case "string-number" -> ENTRY.replace("\"orderId\":15", "\"orderId\":\"15\"");
            case "trace" -> ENTRY.replace("pedido-15", "pedido 15");
            case "duplicate" -> ENTRY.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
            case "trailing" -> ENTRY + "{}";
            default -> "";
        };
        assertThrows(RuntimeException.class, () -> timeline.deserialize("audit.timeline", payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void recovererRecordsTheRejectionAndUsesTheTopicSpecificDeadLetter() {
        var projection = mock(ReportProjection.class);
        var deadLetters = mock(DeadLetterPublishingRecoverer.class);
        var recoverer = new RejectedEventRecoverer(projection, JsonMapper.builder().build(), deadLetters, ".report.DLT");
        var record = new ConsumerRecord<String, Object>("audit.timeline", 1, 7L, "15", null);
        var failure = new ListenerExecutionFailedException("l",
                new DeserializationException("bad", "{}".getBytes(StandardCharsets.UTF_8), false, new IllegalArgumentException()));
        recoverer.accept(record, null, failure);
        verify(projection).reject("audit.timeline", 1, 7L, "INVALID_EVENT", "{}".getBytes(StandardCharsets.UTF_8));
        verify(deadLetters).accept(record, null, failure);
    }
}
