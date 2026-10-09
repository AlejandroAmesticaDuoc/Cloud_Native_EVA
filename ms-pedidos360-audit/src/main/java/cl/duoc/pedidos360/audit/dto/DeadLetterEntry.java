package cl.duoc.pedidos360.audit.dto;

import java.time.Instant;

public record DeadLetterEntry(long id, String messageId, String sourceQueue, String reason, long deathCount,
        String originalExchange, String originalRoutingKey, String messageType, String correlationId, String traceId,
        String payload, String payloadSha256, int payloadBytes, Instant firstDeathAt, Instant recordedAt) {}
