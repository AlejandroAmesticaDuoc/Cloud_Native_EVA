package cl.duoc.pedidos360.audit.dto;

import java.util.List;

public record DeadLetterPage(List<DeadLetterEntry> items, Long nextAfterId) {}
