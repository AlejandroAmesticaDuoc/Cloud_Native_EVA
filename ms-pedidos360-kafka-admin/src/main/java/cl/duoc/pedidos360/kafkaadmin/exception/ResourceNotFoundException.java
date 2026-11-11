package cl.duoc.pedidos360.kafkaadmin.exception;

/** El tópico o el consumer group indicado no existe (404). */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
