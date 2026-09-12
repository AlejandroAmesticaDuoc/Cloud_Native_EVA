package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.dto.audit.AuditPage;
import cl.duoc.pedidos360.bff.service.AuditService;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditService store;

    public AuditController(AuditService store) {
        this.store = store;
    }

    @GetMapping
    public AuditPage list(@RequestParam(defaultValue = "0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return store.find(null, afterId, size);
    }

    @GetMapping("/orders/{orderId}")
    public AuditPage order(@PathVariable @Positive long orderId,
            @RequestParam(defaultValue = "0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return store.find(orderId, afterId, size);
    }
}
