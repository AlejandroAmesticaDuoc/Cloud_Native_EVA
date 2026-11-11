package cl.duoc.pedidos360.kafkaadmin.dto;

/** Resumen de un tópico en el listado. */
public record TopicSummary(String name, int partitions, int replicationFactor, boolean internal, boolean protectedTopic) {}
