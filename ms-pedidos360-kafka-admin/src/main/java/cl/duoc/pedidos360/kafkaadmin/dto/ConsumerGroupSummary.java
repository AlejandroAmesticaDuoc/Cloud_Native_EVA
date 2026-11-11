package cl.duoc.pedidos360.kafkaadmin.dto;

/** Resumen de un consumer group en el listado (estado: STABLE, EMPTY, PREPARING_REBALANCE...). */
public record ConsumerGroupSummary(String groupId, String state) {}
