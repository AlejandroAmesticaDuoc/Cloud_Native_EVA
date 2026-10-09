package cl.duoc.pedidos360.mqadmin.exception;

/**
 * Configuración inválida que solo se detecta mirando el estado del broker, por ejemplo comodines
 * en un exchange que no es topic o un exchange de dead letter inexistente (400).
 */
public class InvalidAdminRequestException extends RuntimeException {
    public InvalidAdminRequestException(String message) {
        super(message);
    }
}
