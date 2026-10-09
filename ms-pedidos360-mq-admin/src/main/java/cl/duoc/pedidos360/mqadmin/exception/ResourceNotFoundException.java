package cl.duoc.pedidos360.mqadmin.exception;

/** La cola, el exchange, el binding o la DLQ indicada no existe (404). */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
