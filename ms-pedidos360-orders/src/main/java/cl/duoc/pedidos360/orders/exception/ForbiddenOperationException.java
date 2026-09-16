package cl.duoc.pedidos360.orders.exception;

public class ForbiddenOperationException extends RuntimeException {
    public ForbiddenOperationException() { super("El usuario no tiene permisos para acceder a este pedido"); }
    public ForbiddenOperationException(String message) { super(message); }
}
