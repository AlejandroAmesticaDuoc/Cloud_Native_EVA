package cl.duoc.pedidos360.report.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import cl.duoc.pedidos360.report.dto.InvoiceCommand;
import cl.duoc.pedidos360.report.dto.InvoiceResponse;
import cl.duoc.pedidos360.report.exception.InvoiceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite la boleta (documento de texto de demostración) de un pedido entregado. No conoce RabbitMQ.
 * Idempotente: un eventId u orderId ya emitido no genera otra boleta. Si los ítems no suman el total lanza
 * InvoiceRejectedException (error permanente); los errores de base se propagan como transitorios.
 */
@Service
public class InvoiceService {
    private static final Logger LOG = LoggerFactory.getLogger(InvoiceService.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public enum Result { ISSUED, DUPLICATE }

    public InvoiceService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Result issue(InvoiceCommand command) {
        var amounts = InvoiceCalculator.calculate(command);
        Instant issuedAt = clock.instant();
        String folio = folio(command.orderId());
        int inserted = jdbc.update("""
                INSERT INTO report_invoices(event_id, order_id, customer_id, folio, net_amount, tax_amount,
                    total_amount, item_count, document, trace_id, issued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, command.eventId(), command.orderId(), command.customerId(), folio, amounts.net(), amounts.tax(),
                amounts.total(), command.items().size(), document(command, amounts, folio, issuedAt),
                command.traceId(), Timestamp.from(issuedAt));
        if (inserted == 0) {
            LOG.info("Boleta ya emitida; se omite orderId={} eventId={}", command.orderId(), command.eventId());
            return Result.DUPLICATE;
        }
        LOG.info("Boleta emitida folio={} orderId={} total={} traceId={}", folio, command.orderId(),
                amounts.total(), command.traceId());
        return Result.ISSUED;
    }

    @Transactional(readOnly = true)
    public InvoiceResponse find(long orderId) {
        return jdbc.query("""
                SELECT order_id, folio, customer_id, net_amount, tax_amount, total_amount, item_count, document,
                    trace_id, issued_at FROM report_invoices WHERE order_id = ?
                """, (rs, row) -> new InvoiceResponse(rs.getLong("order_id"), rs.getString("folio"),
                rs.getString("customer_id"), rs.getBigDecimal("net_amount"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"), rs.getInt("item_count"), rs.getString("document"),
                rs.getString("trace_id"), rs.getTimestamp("issued_at").toInstant()), orderId)
                .stream().findFirst().orElseThrow(InvoiceNotFoundException::new);
    }

    static String folio(long orderId) {
        return "B-%010d".formatted(orderId);
    }

    private static String document(InvoiceCommand command, InvoiceCalculator.Amounts amounts, String folio,
            Instant issuedAt) {
        var text = new StringBuilder("BOLETA ELECTRÓNICA (demostración)\n")
                .append("Folio: ").append(folio).append('\n')
                .append("Pedido #").append(command.orderId()).append(" | Cliente: ").append(command.customerId()).append('\n')
                .append("Emisión (UTC): ").append(issuedAt).append('\n')
                .append("Detalle:\n");
        for (var item : command.items()) {
            text.append("  ").append(item.quantity()).append(" x Producto #").append(item.productId())
                    .append(" @ ").append(item.unitPrice().toPlainString()).append(" = ")
                    .append(item.unitPrice().multiply(java.math.BigDecimal.valueOf(item.quantity())).toPlainString())
                    .append('\n');
        }
        return text.append("Neto: ").append(amounts.net().toPlainString()).append('\n')
                .append("IVA 19%: ").append(amounts.tax().toPlainString()).append('\n')
                .append("Total: ").append(amounts.total().toPlainString()).append('\n')
                .append("Seguimiento: ").append(command.traceId()).toString();
    }
}
