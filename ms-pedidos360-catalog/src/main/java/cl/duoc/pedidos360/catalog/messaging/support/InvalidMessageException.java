package cl.duoc.pedidos360.catalog.messaging.support;

/**
 * Mensaje que nunca podrá procesarse (content-type, tamaño, JSON, validación o messageId distinto del eventId).
 * ManualAckHandler lo rechaza de inmediato, sin reintentos, para que RabbitMQ lo lleve a la DLQ.
 */
public class InvalidMessageException extends RuntimeException {
    public InvalidMessageException(String reason) {
        super(reason);
    }

    public InvalidMessageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
