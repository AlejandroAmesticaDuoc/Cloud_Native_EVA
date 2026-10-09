package cl.duoc.pedidos360.mqadmin.dto;

/**
 * Profundidad de una DLQ vigilada. {@code alert} es verdadero cuando la cantidad de mensajes alcanza
 * o supera el umbral; {@code exists} es falso si su servicio todavía no declaró la cola.
 */
public record DeadLetterQueueStatus(String queue, boolean exists, long messages, long threshold, boolean alert) {
}
