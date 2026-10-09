package cl.duoc.pedidos360.report.exception;

public class InvoiceNotFoundException extends RuntimeException {
    public InvoiceNotFoundException() {
        super("Boleta no encontrada");
    }
}
