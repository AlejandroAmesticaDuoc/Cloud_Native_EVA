package cl.duoc.pedidos360.catalog.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException() {
        super("Producto no encontrado");
    }
}
