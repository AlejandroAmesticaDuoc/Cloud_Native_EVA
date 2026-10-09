package cl.duoc.pedidos360.notify.messaging.support;

import java.util.UUID;
import java.util.function.Function;
import jakarta.validation.Validator;
import org.springframework.amqp.core.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte un mensaje AMQP en el comando esperado o lanza InvalidMessageException (error permanente -> DLQ).
 * Valida content-type, codificación, tamaño, JSON estricto (sin campos desconocidos, duplicados ni coerciones),
 * Bean Validation y que el messageId, si viene, sea igual al eventId del payload.
 */
public class JsonCommandReader {
    public static final String CONTENT_TYPE = "application/json";
    private final JsonMapper json;
    private final Validator validator;
    private final int maxPayloadBytes;

    public JsonCommandReader(JsonMapper json, Validator validator, int maxPayloadBytes) {
        this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
        this.validator = validator;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public <T> T read(Message message, Class<T> type, Function<T, UUID> eventId) {
        var properties = message.getMessageProperties();
        if (!CONTENT_TYPE.equals(properties.getContentType())) {
            throw new InvalidMessageException("content-type no soportado: " + properties.getContentType());
        }
        String encoding = properties.getContentEncoding();
        if (encoding != null && !"UTF-8".equalsIgnoreCase(encoding)) {
            throw new InvalidMessageException("codificación no soportada: " + encoding);
        }
        byte[] body = message.getBody();
        if (body == null || body.length == 0) throw new InvalidMessageException("cuerpo vacío");
        if (body.length > maxPayloadBytes) {
            throw new InvalidMessageException("tamaño " + body.length + " bytes supera el máximo " + maxPayloadBytes);
        }
        T command;
        try {
            command = json.readValue(body, type);
        } catch (JacksonException exception) {
            throw new InvalidMessageException("JSON inválido: " + exception.getOriginalMessage());
        }
        if (command == null) throw new InvalidMessageException("JSON nulo");
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            var violation = violations.iterator().next();
            throw new InvalidMessageException("validación: " + violation.getPropertyPath() + " " + violation.getMessage());
        }
        String messageId = properties.getMessageId();
        if (messageId != null && !messageId.equals(String.valueOf(eventId.apply(command)))) {
            throw new InvalidMessageException("messageId distinto del eventId del payload");
        }
        return command;
    }
}
