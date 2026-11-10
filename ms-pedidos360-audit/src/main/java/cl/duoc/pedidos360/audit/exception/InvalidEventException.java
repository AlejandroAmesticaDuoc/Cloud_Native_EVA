package cl.duoc.pedidos360.audit.exception;

/**
 * Evento de orders.events bien formado pero imposible de registrar (clave distinta del pedido o eventId/versión
 * ya registrados con otro contenido). No se reintenta: se deja en audit_rejections y en la DLT.
 */
public class InvalidEventException extends RuntimeException {
    private final String reason;

    public InvalidEventException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Código guardado en audit_rejections (INVALID_EVENT o EVENT_CONFLICT). */
    public String reason() {
        return reason;
    }
}
