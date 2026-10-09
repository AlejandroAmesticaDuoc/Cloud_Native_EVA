package cl.duoc.pedidos360.audit.messaging.support;

import java.io.IOException;
import java.time.Duration;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;

/**
 * Tabla de decisiones ACK/NACK de los listeners con {@code AcknowledgeMode.MANUAL}:
 * <pre>
 * | Situación                                   | Acción                                              | Log   |
 * |---------------------------------------------|-----------------------------------------------------|-------|
 * | Procesado OK                                | basicAck(tag, false)                                | INFO  |
 * | Duplicado (eventId ya procesado)            | basicAck (idempotencia, no repite efectos)          | INFO  |
 * | Inválido / veneno (InvalidMessageException) | basicNack(tag, false, false) inmediato -> DLX -> DLQ | WARN  |
 * | Error transitorio (SMTP, base de datos...)  | reintento local acotado con espera exponencial;     | WARN  |
 * |                                             | agotado -> basicNack(tag, false, false) -> DLQ      | ERROR |
 * | Nunca                                       | requeue=true (evita bucles de mensajes veneno)      | -     |
 * </pre>
 * Ninguna excepción escapa: siempre se ejecuta ack o nack. Si el canal está cerrado se registra ERROR;
 * el broker reentregará el mensaje y la idempotencia evita repetir efectos.
 */
public class ManualAckHandler {
    public static final String TRACE_ID_HEADER = "x-trace-id";
    private static final Logger log = LoggerFactory.getLogger(ManualAckHandler.class);
    private final int maxAttempts;
    private final Duration initialInterval;
    private final double multiplier;
    private final Duration maxInterval;

    /** Resultado de un procesamiento correcto; ambos terminan en ack. */
    public enum Outcome { PROCESSED, DUPLICATE }

    /** Lógica del listener: lanza InvalidMessageException si el mensaje nunca podrá procesarse. */
    @FunctionalInterface
    public interface MessageProcessor {
        Outcome process(Message message) throws Exception;
    }

    public ManualAckHandler(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {
        if (maxAttempts < 1 || multiplier < 1 || initialInterval.isNegative() || maxInterval.compareTo(initialInterval) < 0) {
            throw new IllegalArgumentException("Política de reintentos inválida");
        }
        this.maxAttempts = maxAttempts;
        this.initialInterval = initialInterval;
        this.multiplier = multiplier;
        this.maxInterval = maxInterval;
    }

    public void handle(Message message, Channel channel, long deliveryTag, MessageProcessor processor) {
        String context = describe(message);
        Duration wait = initialInterval;
        for (int attempt = 1; ; attempt++) {
            try {
                Outcome outcome = processor.process(message);
                if (outcome == Outcome.DUPLICATE) log.info("[ACK] Duplicado ignorado (eventId ya procesado) {}", context);
                else log.info("[ACK] Mensaje procesado {}", context);
                ack(channel, deliveryTag, context);
                return;
            } catch (InvalidMessageException invalid) {
                log.warn("[DLQ] Mensaje inválido enviado a la DLQ sin reintentos {} motivo={}", context, invalid.getMessage());
                reject(channel, deliveryTag, context);
                return;
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    log.error("[DLQ] Procesamiento interrumpido; mensaje enviado a la DLQ {}", context, error);
                    reject(channel, deliveryTag, context);
                    return;
                }
                if (attempt >= maxAttempts) {
                    log.error("[DLQ] Reintentos agotados ({}/{}); mensaje enviado a la DLQ {}", attempt, maxAttempts,
                            context, error);
                    reject(channel, deliveryTag, context);
                    return;
                }
                log.warn("[RETRY] Error transitorio intento {}/{} {} motivo={}; nuevo intento en {} ms", attempt,
                        maxAttempts, context, error.getClass().getSimpleName() + ": " + error.getMessage(), wait.toMillis());
                if (!pause(wait)) {
                    log.error("[DLQ] Reintentos interrumpidos; mensaje enviado a la DLQ {}", context, error);
                    reject(channel, deliveryTag, context);
                    return;
                }
                wait = next(wait);
            }
        }
    }

    private void ack(Channel channel, long deliveryTag, String context) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException | RuntimeException exception) {
            log.error("[ACK] No se pudo confirmar; el broker lo reentregará {}", context, exception);
        }
    }

    /** nack sin requeue: RabbitMQ lo envía al dead-letter exchange configurado en la cola (cmd.dead.dlx). */
    private void reject(Channel channel, long deliveryTag, String context) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException | RuntimeException exception) {
            log.error("[DLQ] No se pudo rechazar; el broker lo reentregará {}", context, exception);
        }
    }

    private Duration next(Duration current) {
        long millis = (long) Math.min(current.toMillis() * multiplier, maxInterval.toMillis());
        return Duration.ofMillis(millis);
    }

    private static boolean pause(Duration wait) {
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String describe(Message message) {
        var properties = message.getMessageProperties();
        return "cola=" + safe(properties.getConsumerQueue()) + " messageId=" + safe(properties.getMessageId())
                + " type=" + safe(properties.getType()) + " correlationId=" + safe(properties.getCorrelationId())
                + " traceId=" + safe(properties.getHeader(TRACE_ID_HEADER));
    }

    /** Evita inyección en logs: sin caracteres de control y con largo acotado. */
    private static String safe(Object value) {
        if (value == null) return "-";
        String text = value.toString().replaceAll("\\p{Cntrl}", "_");
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
