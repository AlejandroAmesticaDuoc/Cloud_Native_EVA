package cl.duoc.pedidos360.mqadmin.controller;

import java.net.URI;
import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.ApiErrorResponse;
import cl.duoc.pedidos360.mqadmin.dto.BindingRequest;
import cl.duoc.pedidos360.mqadmin.dto.BindingResponse;
import cl.duoc.pedidos360.mqadmin.dto.ResourceNames;
import cl.duoc.pedidos360.mqadmin.service.RabbitAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Un binding se identifica por exchange, cola y routing key; por eso se usan parámetros de consulta. */
@RestController
@Validated
@RequestMapping("/api/v1/mq-admin/bindings")
@Tag(name = "Bindings", description = "Enlazar y desenlazar colas con exchanges")
public class BindingAdminController {
    private final RabbitAdminService service;

    public BindingAdminController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista bindings exchange -> cola, filtrando opcionalmente por exchange y/o cola")
    @ApiResponse(responseCode = "200", description = "Bindings encontrados (sin los implícitos del exchange por defecto)")
    @ApiResponse(responseCode = "404", description = "El exchange o la cola del filtro no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public List<BindingResponse> listBindings(
            @RequestParam(required = false)
            @Pattern(regexp = ResourceNames.READABLE_NAME, message = "El exchange " + ResourceNames.READABLE_NAME_RULE)
            String exchange,
            @RequestParam(required = false)
            @Pattern(regexp = ResourceNames.READABLE_NAME, message = "La cola " + ResourceNames.READABLE_NAME_RULE)
            String queue) {
        return service.listBindings(exchange, queue);
    }

    @PostMapping
    @Operation(summary = "Crea un binding; '*' y '#' solo se aceptan si el exchange es topic")
    @ApiResponse(responseCode = "201", description = "Binding creado; Location permite eliminarlo")
    @ApiResponse(responseCode = "400", description = "Datos inválidos o comodines en un exchange que no es topic",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El exchange o la cola no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El binding ya existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<BindingResponse> createBinding(@Valid @RequestBody BindingRequest request) {
        BindingResponse created = service.createBinding(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .queryParam("exchange", created.exchange())
                .queryParam("queue", created.queue())
                .queryParam("routingKey", created.routingKey())
                .encode().build().toUri();
        return ResponseEntity.created(location).body(created);
    }

    @DeleteMapping
    @Operation(summary = "Elimina el binding indicado por exchange, cola y routing key")
    @ApiResponse(responseCode = "204", description = "Binding eliminado", content = @Content)
    @ApiResponse(responseCode = "404", description = "El exchange, la cola o el binding no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> deleteBinding(
            @RequestParam
            @Pattern(regexp = ResourceNames.NAME, message = "El nombre del exchange " + ResourceNames.NAME_RULE)
            String exchange,
            @RequestParam
            @Pattern(regexp = ResourceNames.NAME, message = "El nombre de la cola " + ResourceNames.NAME_RULE)
            String queue,
            @RequestParam
            @Size(max = 255, message = "La routingKey no puede superar 255 caracteres")
            @Pattern(regexp = ResourceNames.BINDING_KEY, message = "La routingKey " + ResourceNames.BINDING_KEY_RULE)
            String routingKey) {
        service.deleteBinding(exchange, queue, routingKey);
        return ResponseEntity.noContent().build();
    }
}
