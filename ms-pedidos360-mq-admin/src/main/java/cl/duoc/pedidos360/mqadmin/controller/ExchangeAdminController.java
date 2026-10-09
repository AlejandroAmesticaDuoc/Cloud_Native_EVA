package cl.duoc.pedidos360.mqadmin.controller;

import java.net.URI;
import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.ApiErrorResponse;
import cl.duoc.pedidos360.mqadmin.dto.CreateExchangeRequest;
import cl.duoc.pedidos360.mqadmin.dto.ExchangeResponse;
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
@RequestMapping("/api/v1/mq-admin/exchanges")
@Tag(name = "Exchanges", description = "Crear, listar y eliminar exchanges de RabbitMQ")
public class ExchangeAdminController {
    private final RabbitAdminService service;

    public ExchangeAdminController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista los exchanges del vhost, incluidos los propios del broker")
    @ApiResponse(responseCode = "200", description = "Exchanges informados por la API de management")
    public List<ExchangeResponse> listExchanges() {
        return service.listExchanges();
    }

    @PostMapping
    @Operation(summary = "Crea un exchange DIRECT, TOPIC, FANOUT o HEADERS")
    @ApiResponse(responseCode = "201", description = "Exchange creado; Location identifica el recurso")
    @ApiResponse(responseCode = "400", description = "Nombre vacío o inválido, o tipo inexistente", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El exchange ya existe o el nombre es de la topología base",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ExchangeResponse> createExchange(@Valid @RequestBody CreateExchangeRequest request) {
        ExchangeResponse created = service.createExchange(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{name}")
                .buildAndExpand(created.name()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Elimina un exchange; ifUnused=true exige que no tenga bindings")
    @ApiResponse(responseCode = "204", description = "Exchange eliminado", content = @Content)
    @ApiResponse(responseCode = "404", description = "El exchange no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Exchange protegido o con bindings (ifUnused=true)",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> deleteExchange(@PathVariable
            @Pattern(regexp = ResourceNames.NAME, message = "El nombre del exchange " + ResourceNames.NAME_RULE)
            String name,
            @RequestParam(defaultValue = "false") boolean ifUnused) {
        service.deleteExchange(name, ifUnused);
        return ResponseEntity.noContent().build();
    }
}
