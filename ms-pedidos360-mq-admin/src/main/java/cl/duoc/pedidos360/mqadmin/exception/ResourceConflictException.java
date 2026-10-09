package cl.duoc.pedidos360.mqadmin.exception;

/** El recurso ya existe o el broker rechazó la operación por una precondición (409). */
public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
