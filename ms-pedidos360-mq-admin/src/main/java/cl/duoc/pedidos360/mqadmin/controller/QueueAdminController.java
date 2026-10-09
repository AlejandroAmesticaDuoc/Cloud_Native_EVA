package cl.duoc.pedidos360.mqadmin.controller;

import java.net.URI;
import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.ApiErrorResponse;
import cl.duoc.pedidos360.mqadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.mqadmin.dto.PurgeResponse;
import cl.duoc.pedidos360.mqadmin.dto.QueueResponse;
import cl.duoc.pedidos360.mqadmin.dto.ResourceNames;
import cl.duoc.pedidos360.mqadmin.service.RabbitAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Validated
@RequestMapping("/api/v1/mq-admin/queues")
@Tag(name = "Colas", description = "Crear, consultar, vaciar y eliminar colas de RabbitMQ")
public class QueueAdminController {
    private final RabbitAdminService service;

    public QueueAdminController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista las colas del vhost con sus mensajes y consumidores")
    @ApiResponse(responseCode = "200", description = "Colas informadas por la API de management")
    @ApiResponse(responseCode = "503", description = "RabbitMQ o su API de management no disponibles", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public List<QueueResponse> listQueues() {
        return service.listQueues();
    }

    @GetMapping("/{name}")
    @Operation(summary = "Consulta una cola: tipo, argumentos, mensajes y consumidores")
    @ApiResponse(responseCode = "200", description = "Cola encontrada")
    @ApiResponse(responseCode = "400", description = "Nombre inválido", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La cola no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public QueueResponse getQueue(@PathVariable
            @Pattern(regexp = ResourceNames.READABLE_NAME, message = "El nombre de la cola " + ResourceNames.READABLE_NAME_RULE)
            String name) {
        return service.getQueue(name);
    }

    @PostMapping
    @Operation(summary = "Crea una cola CLASSIC o QUORUM con DLX, TTL y largo máximo opcionales")
    @ApiResponse(responseCode = "201", description = "Cola creada; el header Location apunta a la nueva cola")
    @ApiResponse(responseCode = "400", description = "Nombre vacío o inválido, configuración inválida o DLX inexistente",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La cola ya existe o el nombre es de la topología base",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<QueueResponse> createQueue(@Valid @RequestBody CreateQueueRequest request) {
        QueueResponse created = service.createQueue(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{name}")
                .buildAndExpand(created.name()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Elimina una cola; ifUnused/ifEmpty exigen que no tenga consumidores o mensajes")
    @ApiResponse(responseCode = "204", description = "Cola eliminada", content = @Content)
    @ApiResponse(responseCode = "404", description = "La cola no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Cola protegida o precondición ifUnused/ifEmpty no cumplida",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> deleteQueue(@PathVariable
            @Pattern(regexp = ResourceNames.NAME, message = "El nombre de la cola " + ResourceNames.NAME_RULE)
            String name,
            @RequestParam(defaultValue = "false") boolean ifUnused,
            @RequestParam(defaultValue = "false") boolean ifEmpty) {
        service.deleteQueue(name, ifUnused, ifEmpty);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{name}/messages")
    @Operation(summary = "Vacía (purga) los mensajes listos de una cola")
    @ApiResponse(responseCode = "200", description = "Cantidad de mensajes eliminados")
    @ApiResponse(responseCode = "404", description = "La cola no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public PurgeResponse purgeQueue(@PathVariable
            @Pattern(regexp = ResourceNames.NAME, message = "El nombre de la cola " + ResourceNames.NAME_RULE)
            String name) {
        return service.purgeQueue(name);
    }
}
