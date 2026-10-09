package cl.duoc.pedidos360.audit.dto;

import java.time.Instant;

/**
 * Dead letter recibida, ya extraída de las cabeceras AMQP. {@code payload} es null cuando supera el máximo
 * configurado o no es texto UTF-8; en ese caso solo se conservan el hash SHA-256 y el tamaño.
 */
public record DeadLetter(String messageId, String sourceQueue, String reason, long deathCount, String originalExchange,
        String originalRoutingKey, String messageType, String correlationId, String traceId, String payload,
        String payloadSha256, int payloadBytes, Instant firstDeathAt) {}
