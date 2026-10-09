package cl.duoc.pedidos360.audit.messaging.deadletter;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import cl.duoc.pedidos360.audit.dto.DeadLetter;
import cl.duoc.pedidos360.audit.messaging.support.InvalidMessageException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Extrae los datos de auditoría de una dead letter. RabbitMQ agrega {@code x-death} (la muerte más reciente
 * primero, con cola, motivo, conteo, exchange, routing keys y hora) y {@code x-first-death-*}; estas últimas
 * se usan si falta {@code x-death}. Sin ninguna de ellas el mensaje no es una dead letter (inválido -> DLQ).
 */
public class DeadLetterMapper {
    public static final String TRACE_ID_HEADER = "x-trace-id";
    static final String FIRST_DEATH_QUEUE = "x-first-death-queue";
    static final String FIRST_DEATH_REASON = "x-first-death-reason";
    static final String FIRST_DEATH_EXCHANGE = "x-first-death-exchange";
    private final int maxPayloadBytes;

    public DeadLetterMapper(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public DeadLetter map(Message message) {
        MessageProperties properties = message.getMessageProperties();
        List<Map<String, ?>> deaths = properties.getXDeathHeader();
        Map<String, ?> death = deaths == null || deaths.isEmpty() ? Map.of() : deaths.getFirst();
        String queue = first(death.get("queue"), properties.getHeader(FIRST_DEATH_QUEUE));
        if (queue == null) {
            throw new InvalidMessageException("sin cabeceras x-death ni x-first-death-queue: no es una dead letter");
        }
        String reason = first(death.get("reason"), properties.getHeader(FIRST_DEATH_REASON));
        String exchange = first(death.get("exchange"), properties.getHeader(FIRST_DEATH_EXCHANGE));
        String routingKey = death.get("routing-keys") instanceof List<?> keys && !keys.isEmpty()
                ? keys.stream().map(String::valueOf).collect(Collectors.joining(","))
                : properties.getReceivedRoutingKey();
        long count = death.get("count") instanceof Number number ? number.longValue() : 1;
        Instant firstDeathAt = death.get("time") instanceof Date time ? time.toInstant() : null;

        byte[] body = message.getBody() == null ? new byte[0] : message.getBody();
        String sha256 = sha256(body);
        String payload = body.length <= maxPayloadBytes ? utf8(body) : null;
        String messageId = properties.getMessageId();
        if (messageId == null || messageId.isBlank() || messageId.length() > 200) messageId = "sha256:" + sha256;
        return new DeadLetter(clean(messageId, 200), clean(queue, 255), clean(reason == null ? "unknown" : reason, 40),
                Math.max(1, count), clean(exchange, 255), clean(routingKey, 255), clean(properties.getType(), 100),
                clean(properties.getCorrelationId(), 200), clean(properties.getHeader(TRACE_ID_HEADER), 100),
                payload, sha256, body.length, firstDeathAt);
    }

    private static String first(Object value, Object fallback) {
        Object chosen = value != null ? value : fallback;
        return chosen == null ? null : chosen.toString();
    }

    /** Valores de cabecera enviados por terceros: sin caracteres de control y con largo acotado a la columna. */
    private static String clean(Object value, int max) {
        if (value == null) return null;
        String text = value.toString().replaceAll("\\p{Cntrl}", "_");
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String utf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
