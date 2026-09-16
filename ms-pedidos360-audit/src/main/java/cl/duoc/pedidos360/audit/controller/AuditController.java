package cl.duoc.pedidos360.audit.controller;

import cl.duoc.pedidos360.audit.dto.AuditPage;
import cl.duoc.pedidos360.audit.service.AuditStore;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditStore store;

    public AuditController(AuditStore store) {
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
