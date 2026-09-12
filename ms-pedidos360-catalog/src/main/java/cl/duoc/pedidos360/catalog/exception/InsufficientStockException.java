package cl.duoc.pedidos360.catalog.exception;

public class InsufficientStockException extends StockConflictException {
    public InsufficientStockException() {
        super("Stock insuficiente para aceptar el pedido");
    }
}
