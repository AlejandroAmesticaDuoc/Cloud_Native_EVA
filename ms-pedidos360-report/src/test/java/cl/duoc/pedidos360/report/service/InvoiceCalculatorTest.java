package cl.duoc.pedidos360.report.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cl.duoc.pedidos360.report.dto.InvoiceCommand;
import cl.duoc.pedidos360.report.exception.InvoiceRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InvoiceCalculatorTest {
    static InvoiceCommand command(String total, InvoiceCommand.Item... items) {
        return new InvoiceCommand(1, UUID.randomUUID(), 42L, "cliente-demo", List.of(items), new BigDecimal(total),
                Instant.parse("2026-10-08T18:00:00Z"), "trace-demo-001");
    }

    static InvoiceCommand.Item item(long product, int quantity, String price) {
        return new InvoiceCommand.Item(product, quantity, new BigDecimal(price));
    }

    @Test
    void splitsTheContractExampleIntoNetAndVat() {
        var amounts = InvoiceCalculator.calculate(command("5990.00", item(7, 2, "1500.00"), item(9, 1, "2990.00")));
        assertEquals(new BigDecimal("5033.61"), amounts.net());
        assertEquals(new BigDecimal("956.39"), amounts.tax());
        assertEquals(new BigDecimal("5990.00"), amounts.total());
    }

    @ParameterizedTest
    @CsvSource({"2990.00,2512.61,477.39", "100.00,84.03,15.97", "11.90,10.00,1.90", "0.01,0.01,0.00", "1,0.84,0.16"})
    void roundsTheNetHalfUpAndKeepsNetPlusVatEqualToTotal(String total, String net, String tax) {
        var amounts = InvoiceCalculator.calculate(command(total, item(1, 1, total)));
        assertEquals(new BigDecimal(net), amounts.net());
        assertEquals(new BigDecimal(tax), amounts.tax());
        assertEquals(0, amounts.net().add(amounts.tax()).compareTo(new BigDecimal(total)));
    }

    @Test
    void acceptsEquivalentScalesForTheTotal() {
        var amounts = InvoiceCalculator.calculate(command("3000", item(7, 2, "1500.00")));
        assertEquals(new BigDecimal("3000.00"), amounts.total());
    }

    @Test
    void rejectsItemsThatDoNotAddUpToTheTotal() {
        var error = assertThrows(InvoiceRejectedException.class,
                () -> InvoiceCalculator.calculate(command("5990.01", item(7, 2, "1500.00"), item(9, 1, "2990.00"))));
        assertTrue(error.getMessage().contains("5990.00"));
    }

    @Test
    void formatsTheFolioFromTheOrder() {
        assertEquals("B-0000000042", InvoiceService.folio(42));
    }
}
