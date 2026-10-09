package cl.duoc.pedidos360.catalog.controller;

import cl.duoc.pedidos360.catalog.dto.KitchenTicketResponse;
import cl.duoc.pedidos360.catalog.service.KitchenTicketService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalog/kitchen-tickets")
public class KitchenTicketController {
    private final KitchenTicketService service;

    public KitchenTicketController(KitchenTicketService service) {
        this.service = service;
    }

    /**
     * Ticket de cocina generado al consumir el comando {@code kitchen.ticket} del pedido (ADMIN u OPERADOR + scope).
     * 404 si el comando aún no se procesó o el pedido no fue aceptado.
     */
    @GetMapping("/{orderId}")
    @ApiResponse(responseCode = "404", description = "El pedido no tiene ticket de cocina", content = @Content)
    public KitchenTicketResponse getTicket(@PathVariable @Positive long orderId) {
        return service.find(orderId);
    }
}
