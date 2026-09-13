package cl.duoc.pedidos360.audit.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import cl.duoc.pedidos360.audit.dto.OrderEvent;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventParser {
    private static final Set<String> FIELDS = Set.of("schemaVersion", "eventId", "eventType", "orderId",
            "aggregateVersion", "occurredAt", "traceId", "actorId", "customerId", "previousStatus",
            "status", "createdAt", "total");
    private final JsonMapper json;
    private final Validator validator;

    public EventParser(JsonMapper json, Validator validator) {
        this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
        this.validator = validator;
    }

    public OrderEvent parse(String key, String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 32768) {
            throw new IllegalArgumentException("INVALID_EVENT");
        }
        var tree = json.readTree(payload);
        if (!tree.isObject() || !tree.propertyNames().equals(FIELDS)) throw new IllegalArgumentException("INVALID_EVENT");
        OrderEvent event = json.treeToValue(tree, OrderEvent.class);
        if (!validator.validate(event).isEmpty() || !event.validTransition()
                || !String.valueOf(event.orderId()).equals(key)) {
            throw new IllegalArgumentException("INVALID_EVENT");
        }
        return new OrderEvent(event.schemaVersion(), event.eventId(), event.eventType(), event.orderId(),
                event.aggregateVersion(), event.occurredAt(), event.traceId(), event.actorId(), event.customerId(),
                event.previousStatus(), event.status(), event.createdAt(), event.total().setScale(2));
    }

    public String serialize(OrderEvent event) {
        return json.writeValueAsString(event);
    }
}
