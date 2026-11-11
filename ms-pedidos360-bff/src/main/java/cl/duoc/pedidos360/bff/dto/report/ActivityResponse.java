package cl.duoc.pedidos360.bff.dto.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Actividad en tiempo real de la ventana [from, to), calculada por Report desde el tópico Kafka audit.timeline:
 * acciones por tipo, actores más activos, acciones por minuto y momento del último registro.
 */
public record ActivityResponse(Instant from, Instant to, Long totalEvents, Map<String, Long> byAction,
        List<ActorActivity> topActors, List<MinuteActivity> perMinute, Instant lastRecordedAt) {

    public record ActorActivity(String actorId, Long events) {}

    public record MinuteActivity(Instant minute, Long events) {}
}
