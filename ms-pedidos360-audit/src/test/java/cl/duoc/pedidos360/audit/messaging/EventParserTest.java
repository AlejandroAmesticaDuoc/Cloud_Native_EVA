package cl.duoc.pedidos360.audit.messaging;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

public class EventParserTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final EventParser PARSER = new EventParser(JSON, Validation.buildDefaultValidatorFactory().getValidator());

    public static String event(long order, long version, String type, String previous, String status) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"%s","orderId":%d,
                "aggregateVersion":%d,"occurredAt":"2026-09-12T12:00:00Z","traceId":"audit-test",
                "actorId":"operator","customerId":"alice","previousStatus":%s,"status":"%s",
                "createdAt":"2026-09-12T11:00:00Z","total":100.50}
                """.formatted(UUID.randomUUID(), type, order, version,
                        previous == null ? "null" : "\"" + previous + "\"", status);
    }

    @Test
    void parsesAndNormalizesCreatedEvent() {
        var event = PARSER.parse("10", event(10, 1, "OrderCreated", null, "CREADO"));
        assertEquals(10L, event.orderId());
        assertEquals("100.50", event.total().toPlainString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderAccepted|CREADO|ACEPTADO", "OrderCancelled|CREADO|CANCELADO",
            "OrderCancelled|ACEPTADO|CANCELADO", "OrderStatusChanged|ACEPTADO|EN_PREPARACION",
            "OrderStatusChanged|EN_PREPARACION|DESPACHADO", "OrderStatusChanged|DESPACHADO|ENTREGADO"})
    void acceptsValidTransitionsIncludingFirstLegacyEvent(String transition) {
        String[] values = transition.split("\\|");
        assertNotNull(PARSER.parse("10", event(10, 1, values[0], values[1], values[2])));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "eventId", "eventType", "orderId", "aggregateVersion", "occurredAt",
            "traceId", "actorId", "customerId", "previousStatus", "status", "createdAt", "total"})
    void rejectsMissingFields(String field) {
        var tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(event(10, 1, "OrderCreated", null, "CREADO"));
        tree.remove(field);
        assertThrows(RuntimeException.class, () -> PARSER.parse("10", tree.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{", "oversize", "key", "unknown", "duplicate",
            "fractional", "string-number", "version", "negative", "trace", "transition", "extra-json", "scale"})
    void rejectsMalformedOrInconsistentEvents(String scenario) {
        String payload = event(10, 1, "OrderCreated", null, "CREADO");
        String key = "10";
        payload = switch (scenario) {
            case "null", "[]", "{}", "{" -> scenario;
            case "oversize" -> " ".repeat(32769);
            case "unknown" -> payload.replace("\"total\":100.50", "\"total\":100.50,\"token\":\"secret\"");
            case "duplicate" -> payload.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
            case "fractional" -> payload.replace("\"orderId\":10", "\"orderId\":10.5");
            case "string-number" -> payload.replace("\"orderId\":10", "\"orderId\":\"10\"");
            case "version" -> payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            case "negative" -> payload.replace("100.50", "-100.50");
            case "trace" -> payload.replace("audit-test", "invalid trace");
            case "transition" -> event(10, 2, "OrderAccepted", "DESPACHADO", "ACEPTADO");
            case "extra-json" -> payload + "{}";
            case "scale" -> payload.replace("100.50", "100.501");
            default -> payload;
        };
        if (scenario.equals("key")) key = "11";
        String finalPayload = payload;
        String finalKey = key;
        assertThrows(RuntimeException.class, () -> PARSER.parse(finalKey, finalPayload));
    }
}
