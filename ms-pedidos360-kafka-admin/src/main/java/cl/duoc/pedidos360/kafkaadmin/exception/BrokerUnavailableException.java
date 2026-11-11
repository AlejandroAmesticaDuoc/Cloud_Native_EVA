package cl.duoc.pedidos360.kafkaadmin.exception;

/** El clúster Kafka no responde dentro del timeout (503). */
public class BrokerUnavailableException extends RuntimeException {
    public BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
