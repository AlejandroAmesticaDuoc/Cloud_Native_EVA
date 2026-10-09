package cl.duoc.pedidos360.report.exception;

/** Regla de negocio imposible de cumplir para una boleta (por ejemplo, ítems que no suman el total): no se reintenta. */
public class InvoiceRejectedException extends RuntimeException {
    public InvoiceRejectedException(String message) {
        super(message);
    }
}
