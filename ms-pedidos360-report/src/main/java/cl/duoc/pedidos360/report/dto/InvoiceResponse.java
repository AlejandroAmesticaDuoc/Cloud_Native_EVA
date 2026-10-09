package cl.duoc.pedidos360.report.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceResponse(long orderId, String folio, String customerId, BigDecimal netAmount,
        BigDecimal taxAmount, BigDecimal totalAmount, int itemCount, String document, String traceId,
        Instant issuedAt) {}
