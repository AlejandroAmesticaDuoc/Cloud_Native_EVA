package cl.duoc.pedidos360.orders.exception;

public class StockRejectedException extends RuntimeException {
    public StockRejectedException() { super("Catalog rechazó el movimiento; revisa productos y stock"); }
}
