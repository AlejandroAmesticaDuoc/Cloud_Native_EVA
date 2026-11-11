package cl.duoc.pedidos360.kafkaadmin.controller;

import java.util.List;
import cl.duoc.pedidos360.kafkaadmin.dto.*;
import cl.duoc.pedidos360.kafkaadmin.service.KafkaAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/kafka-admin/consumer-groups")
@Tag(name = "Consumer groups", description = "Inspeccionar lag, reiniciar offsets y eliminar consumer groups")
public class ConsumerGroupAdminController {
    private final KafkaAdminService service;

    public ConsumerGroupAdminController(KafkaAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista los consumer groups y su estado")
    @ApiResponse(responseCode = "200", description = "Consumer groups del clúster")
    public List<ConsumerGroupSummary> listGroups() {
        return service.listConsumerGroups();
    }

    @GetMapping("/{groupId}")
    @Operation(summary = "Miembros, particiones asignadas y lag por partición del consumer group")
    @ApiResponse(responseCode = "200", description = "Consumer group encontrado")
    @ApiResponse(responseCode = "404", description = "El consumer group no existe",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ConsumerGroupDetail getGroup(@PathVariable
            @Pattern(regexp = ResourceNames.GROUP, message = "El consumer group " + ResourceNames.GROUP_RULE) String groupId) {
        return service.getConsumerGroup(groupId);
    }

    @PostMapping("/{groupId}/offsets/reset")
    @Operation(summary = "Reinicia los offsets del grupo en un tópico (EARLIEST, LATEST o TIMESTAMP)")
    @ApiResponse(responseCode = "200", description = "Offsets reiniciados")
    @ApiResponse(responseCode = "400", description = "Estrategia o fecha inválida",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El tópico no existe",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El grupo tiene consumidores activos",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResetOffsetsResponse resetOffsets(@PathVariable
            @Pattern(regexp = ResourceNames.GROUP, message = "El consumer group " + ResourceNames.GROUP_RULE) String groupId,
            @Valid @RequestBody ResetOffsetsRequest request) {
        return service.resetOffsets(groupId, request);
    }

    @DeleteMapping("/{groupId}")
    @Operation(summary = "Elimina un consumer group sin consumidores activos")
    @ApiResponse(responseCode = "204", description = "Consumer group eliminado", content = @Content)
    @ApiResponse(responseCode = "404", description = "El consumer group no existe",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El grupo tiene consumidores activos",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> deleteGroup(@PathVariable
            @Pattern(regexp = ResourceNames.GROUP, message = "El consumer group " + ResourceNames.GROUP_RULE) String groupId) {
        service.deleteConsumerGroup(groupId);
        return ResponseEntity.noContent().build();
    }
}
