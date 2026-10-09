package cl.duoc.pedidos360.catalog.exception;

public class KitchenTicketNotFoundException extends RuntimeException {
    public KitchenTicketNotFoundException() {
        super("Ticket de cocina no encontrado");
    }
}
