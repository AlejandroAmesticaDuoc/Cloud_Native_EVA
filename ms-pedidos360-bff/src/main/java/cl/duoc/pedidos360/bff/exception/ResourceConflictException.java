package cl.duoc.pedidos360.bff.exception;

public class ResourceConflictException
        extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }
}