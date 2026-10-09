package cl.duoc.pedidos360.mqadmin.dto;

/** Resultado de vaciar una cola: cantidad de mensajes eliminados. */
public record PurgeResponse(String queue, long purged) {
}
