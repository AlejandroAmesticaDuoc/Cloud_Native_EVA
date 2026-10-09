package cl.duoc.pedidos360.report.controller;

import cl.duoc.pedidos360.report.dto.InvoiceResponse;
import cl.duoc.pedidos360.report.service.InvoiceService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports/invoices")
public class InvoiceController {
    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    /**
     * Boleta emitida al consumir el comando {@code invoice.gen} del pedido (rol ADMIN + scope).
     * 404 si el pedido aún no se entrega o el comando no se procesó.
     */
    @GetMapping("/{orderId}")
    @ApiResponse(responseCode = "404", description = "El pedido no tiene boleta", content = @Content)
    public InvoiceResponse invoice(@PathVariable @Positive long orderId) {
        return invoices.find(orderId);
    }
}
