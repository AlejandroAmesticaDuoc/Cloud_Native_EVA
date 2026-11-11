package cl.duoc.pedidos360.kafkaadmin.exception;

/** El recurso ya existe, está protegido o el clúster rechazó la operación por su estado actual (409). */
public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
