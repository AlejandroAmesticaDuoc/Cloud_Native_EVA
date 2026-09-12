package cl.duoc.pedidos360.catalog.controller;

import cl.duoc.pedidos360.catalog.dto.StockDeductionRequest;
import cl.duoc.pedidos360.catalog.service.StockService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/catalog/stock/deductions")
@Tag(name = "Stock interno", description = "Integración privada con Orders; no publicar en API Gateway")
public class StockController {
    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @PostMapping
    @ApiResponse(responseCode = "204", description = "Stock descontado o solicitud ya procesada", content = @Content)
    public ResponseEntity<Void> deduct(@Valid @RequestBody StockDeductionRequest request) {
        service.deduct(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orderId}/release")
    @ApiResponse(responseCode = "204", description = "Stock devuelto o devolución ya procesada", content = @Content)
    public ResponseEntity<Void> release(@PathVariable @Positive long orderId) {
        service.release(orderId);
        return ResponseEntity.noContent().build();
    }
}
