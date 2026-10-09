package cl.duoc.pedidos360.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import cl.duoc.pedidos360.report.dto.InvoiceCommand;
import cl.duoc.pedidos360.report.exception.InvoiceRejectedException;

/**
 * Cálculo puro de la boleta: el total incluye IVA 19 %. Neto = total / 1,19 con 2 decimales (HALF_UP);
 * IVA = total - neto, así neto + IVA siempre coincide con el total. La suma de quantity * unitPrice debe ser el total.
 */
public final class InvoiceCalculator {
    private static final BigDecimal VAT_FACTOR = new BigDecimal("1.19");

    public record Amounts(BigDecimal net, BigDecimal tax, BigDecimal total) {}

    private InvoiceCalculator() {}

    public static Amounts calculate(InvoiceCommand command) {
        BigDecimal sum = command.items().stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(command.total()) != 0) {
            throw new InvoiceRejectedException("La suma de los ítems (" + sum.toPlainString()
                    + ") no coincide con el total (" + command.total().toPlainString() + ")");
        }
        BigDecimal total = command.total().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal net = total.divide(VAT_FACTOR, 2, RoundingMode.HALF_UP);
        return new Amounts(net, total.subtract(net), total);
    }
}
