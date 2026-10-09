package cl.duoc.pedidos360.mqadmin.dto;

/**
 * Resultado del reproceso: {@code replayed} mensajes confirmados por el broker en su destino original
 * y {@code failed} mensajes que no se pudieron reenviar y siguen en la DLQ.
 */
public record ReplayResponse(int replayed, int failed) {
}
