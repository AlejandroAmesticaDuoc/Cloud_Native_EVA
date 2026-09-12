package cl.duoc.pedidos360.orders.exception;

public class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException() { super("No se pudo confirmar la operación con Catalog; reintenta la misma acción"); }
    public CatalogUnavailableException(String message) { super(message); }
}
