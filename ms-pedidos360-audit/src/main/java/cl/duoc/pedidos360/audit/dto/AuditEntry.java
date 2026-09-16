package cl.duoc.pedidos360.audit.dto;

import java.time.Instant;

public record AuditEntry(long id, OrderEvent event, Instant recordedAt) {}
