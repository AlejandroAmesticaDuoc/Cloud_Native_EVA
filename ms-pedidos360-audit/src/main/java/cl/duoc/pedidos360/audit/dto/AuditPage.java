package cl.duoc.pedidos360.audit.dto;

import java.util.List;

public record AuditPage(List<AuditEntry> items, Long nextAfterId) {}
