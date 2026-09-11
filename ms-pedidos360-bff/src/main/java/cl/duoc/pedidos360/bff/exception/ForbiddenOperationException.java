package cl.duoc.pedidos360.bff.exception;

public class ForbiddenOperationException
        extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}