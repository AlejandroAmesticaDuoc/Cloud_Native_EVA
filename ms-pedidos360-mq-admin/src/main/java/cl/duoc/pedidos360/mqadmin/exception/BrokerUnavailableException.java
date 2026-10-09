package cl.duoc.pedidos360.mqadmin.exception;

/** RabbitMQ o su API de management no responden o rechazaron las credenciales (503). */
public class BrokerUnavailableException extends RuntimeException {
    public BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
