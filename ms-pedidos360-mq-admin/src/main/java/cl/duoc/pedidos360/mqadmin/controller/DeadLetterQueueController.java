package cl.duoc.pedidos360.mqadmin.controller;

import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.ApiErrorResponse;
import cl.duoc.pedidos360.mqadmin.dto.DeadLetterQueueStatus;
import cl.duoc.pedidos360.mqadmin.dto.ReplayRequest;
import cl.duoc.pedidos360.mqadmin.dto.ReplayResponse;
import cl.duoc.pedidos360.mqadmin.dto.ResourceNames;
import cl.duoc.pedidos360.mqadmin.service.RabbitAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/mq-admin/dead-letter-queues")
@Tag(name = "DLQ", description = "Profundidad, alertas y reproceso de las colas de mensajes fallidos")
public class DeadLetterQueueController {
    private final RabbitAdminService service;

    public DeadLetterQueueController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Profundidad, umbral y alerta de cada DLQ configurada")
    @ApiResponse(responseCode = "200", description = "Estado de las DLQ")
    public List<DeadLetterQueueStatus> listDeadLetterQueues() {
        return service.deadLetterQueues();
    }

    @PostMapping("/{name}/replay")
    @Operation(summary = "Reenvía mensajes de una DLQ a su exchange y routing key originales")
    @ApiResponse(responseCode = "200", description = "Mensajes reenviados (replayed) y los que siguen en la DLQ (failed)")
    @ApiResponse(responseCode = "400", description = "maxMessages fuera de 1..100", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No es una DLQ configurada o no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public ReplayResponse replay(@PathVariable
            @Pattern(regexp = ResourceNames.NAME, message = "El nombre de la DLQ " + ResourceNames.NAME_RULE)
            String name,
            @Valid @RequestBody ReplayRequest request) {
        return service.replayDeadLetters(name, request.maxMessages());
    }
}
