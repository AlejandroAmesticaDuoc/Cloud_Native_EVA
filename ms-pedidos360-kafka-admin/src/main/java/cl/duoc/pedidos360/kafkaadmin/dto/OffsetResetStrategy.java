package cl.duoc.pedidos360.kafkaadmin.dto;

/** Posición a la que se reinicia un consumer group: inicio del tópico, final o primer mensaje desde una fecha. */
public enum OffsetResetStrategy { EARLIEST, LATEST, TIMESTAMP }
