package cl.duoc.pedidos360.mqadmin.dto;

import java.time.Instant;

public record ApiErrorResponse(
        Instant timestamp, int status, String error, String message, String path, String traceId) {
}
