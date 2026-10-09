package cl.duoc.pedidos360.audit.controller;

import cl.duoc.pedidos360.audit.dto.DeadLetterPage;
import cl.duoc.pedidos360.audit.service.DeadLetterStore;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit/dead-letters")
public class DeadLetterController {
    private final DeadLetterStore store;

    public DeadLetterController(DeadLetterStore store) {
        this.store = store;
    }

    /**
     * Dead letters auditadas (cola de origen, motivo, conteo, exchange y routing key originales, payload o su hash),
     * paginadas por cursor: {@code nextAfterId} se usa como {@code afterId} de la siguiente página. ADMIN o AUDITOR.
     */
    @GetMapping
    public DeadLetterPage list(@RequestParam(defaultValue = "0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return store.find(afterId, size);
    }
}
