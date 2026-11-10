package cl.duoc.pedidos360.audit.service;

import cl.duoc.pedidos360.audit.dto.AuditTimelineEntry;

/** Puerto de salida hacia audit.timeline. AuditStore no conoce Kafka; la implementación vive en messaging. */
public interface TimelinePublisher {
    /** Publica y espera la confirmación del broker; si falla lanza una excepción transitoria. */
    void publish(AuditTimelineEntry entry);
}
