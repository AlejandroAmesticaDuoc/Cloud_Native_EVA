package cl.duoc.pedidos360.orders.exception;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException() { super("Pedido no encontrado"); }
    public OrderNotFoundException(String message) { super(message); }
}
