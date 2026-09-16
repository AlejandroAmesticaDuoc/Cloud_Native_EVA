package cl.duoc.pedidos360.orders.exception;

public class OrderConflictException extends RuntimeException {
    public OrderConflictException() { super("Existe un conflicto con el estado del pedido"); }
    public OrderConflictException(String message) { super(message); }
}
