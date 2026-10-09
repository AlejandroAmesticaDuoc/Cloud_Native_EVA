package cl.duoc.pedidos360.audit.messaging.support;

/**
 * Mensaje que nunca podrá procesarse (por ejemplo, llegó a la cola de auditoría sin cabeceras {@code x-death}).
 * ManualAckHandler lo rechaza de inmediato, sin reintentos, para que RabbitMQ lo lleve a la DLQ de auditoría.
 */
public class InvalidMessageException extends RuntimeException {
    public InvalidMessageException(String reason) {
        super(reason);
    }

    public InvalidMessageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
