package cl.duoc.pedidos360.report.exception;

/**
 * Mensaje bien formado pero imposible de proyectar (clave distinta del pedido, fechas incoherentes o
 * eventId/versión ya registrados con otro contenido). No se reintenta: se deja en report_rejections y en la DLT.
 */
public class InvalidEventException extends RuntimeException {
    private final String reason;

    public InvalidEventException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Código guardado en report_rejections (INVALID_EVENT o EVENT_CONFLICT). */
    public String reason() {
        return reason;
    }
}
