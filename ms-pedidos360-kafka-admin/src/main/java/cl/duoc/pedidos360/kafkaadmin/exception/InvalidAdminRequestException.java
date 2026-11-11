package cl.duoc.pedidos360.kafkaadmin.exception;

/**
 * Configuración inválida que solo se detecta contra el clúster, por ejemplo un factor de replicación mayor
 * que la cantidad de brokers, reducir particiones o un valor de configuración rechazado por Kafka (400).
 */
public class InvalidAdminRequestException extends RuntimeException {
    public InvalidAdminRequestException(String message) {
        super(message);
    }
}
