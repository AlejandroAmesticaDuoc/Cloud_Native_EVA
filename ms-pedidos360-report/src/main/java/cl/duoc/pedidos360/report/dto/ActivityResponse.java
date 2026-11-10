package cl.duoc.pedidos360.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Métricas de actividad en tiempo real calculadas desde audit.timeline para la ventana [from, to):
 * total de acciones, acciones por tipo, actores más activos, acciones por minuto y frescura de los datos.
 */
public record ActivityResponse(Instant from, Instant to, long totalEvents, Map<OrderEvent.EventType, Long> byAction,
        List<ActorActivity> topActors, List<MinuteActivity> perMinute, Instant lastRecordedAt) {

    public record ActorActivity(String actorId, long events) {}

    public record MinuteActivity(Instant minute, long events) {}
}
