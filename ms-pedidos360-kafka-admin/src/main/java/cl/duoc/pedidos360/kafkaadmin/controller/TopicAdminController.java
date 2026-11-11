package cl.duoc.pedidos360.kafkaadmin.controller;

import java.net.URI;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Validated
@RequestMapping("/api/v1/kafka-admin/topics")
@Tag(name = "Tópicos", description = "Crear, listar, configurar, ampliar y eliminar tópicos de Kafka")
public class TopicAdminController {
    private final KafkaAdminService service;

    public TopicAdminController(KafkaAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista los tópicos con sus particiones y factor de replicación")
    @ApiResponse(responseCode = "200", description = "Tópicos del clúster")
    @ApiResponse(responseCode = "503", description = "Kafka no disponible", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public List<TopicSummary> listTopics(@RequestParam(defaultValue = "false") boolean includeInternal) {
        return service.listTopics(includeInternal);
    }

    @GetMapping("/{name}")
    @Operation(summary = "Detalle de un tópico: líder, réplicas e ISR por partición y configuraciones vigentes")
    @ApiResponse(responseCode = "200", description = "Tópico encontrado")
    @ApiResponse(responseCode = "400", description = "Nombre inválido", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El tópico no existe", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public TopicDetail getTopic(@PathVariable
            @Pattern(regexp = ResourceNames.READABLE_TOPIC, message = "El nombre del tópico " + ResourceNames.READABLE_TOPIC_RULE)
            String name) {
        return service.getTopic(name);
    }

    @PostMapping
    @Operation(summary = "Crea un tópico con particiones, factor de replicación y configuraciones")
    @ApiResponse(responseCode = "201", description = "Tópico creado; el header Location apunta al nuevo tópico")
    @ApiResponse(responseCode = "400", description = "Nombre, particiones, réplicas o configuraciones inválidas",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El tópico ya existe",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<TopicDetail> createTopic(@Valid @RequestBody CreateTopicRequest request) {
        TopicDetail created = service.createTopic(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{name}").buildAndExpand(created.name()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PatchMapping("/{name}/configs")
    @Operation(summary = "Modifica configuraciones del tópico (retención, cleanup policy, min.insync.replicas...)")
    @ApiResponse(responseCode = "200", description = "Configuración aplicada; devuelve el tópico actualizado")
    @ApiResponse(responseCode = "400", description = "Configuración no permitida o valor inválido",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El tópico no existe",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public TopicDetail updateConfigs(@PathVariable
            @Pattern(regexp = ResourceNames.TOPIC, message = "El nombre del tópico " + ResourceNames.TOPIC_RULE) String name,
            @Valid @RequestBody UpdateTopicConfigsRequest request) {
        return service.updateTopicConfigs(name, request);
    }

    @PatchMapping("/{name}/partitions")
    @Operation(summary = "Aumenta el número de particiones (no se permite en tópicos base)")
    @ApiResponse(responseCode = "200", description = "Particiones aumentadas; devuelve el tópico actualizado")
    @ApiResponse(responseCode = "400", description = "El total no es mayor que el actual o supera el máximo",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El tópico no existe",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Tópico base protegido",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public TopicDetail increasePartitions(@PathVariable
            @Pattern(regexp = ResourceNames.TOPIC, message = "El nombre del tópico " + ResourceNames.TOPIC_RULE) String name,
            @Valid @RequestBody PartitionsRequest request) {
        return service.increasePartitions(name, request.totalPartitions());
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Elimina un tópico (los tópicos base y los internos están protegidos)")
    @ApiResponse(responseCode = "204", description = "Tópico eliminado", content = @Content)
    @ApiResponse(responseCode = "404", description = "El tópico no existe",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Tópico base protegido",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> deleteTopic(@PathVariable
            @Pattern(regexp = ResourceNames.TOPIC, message = "El nombre del tópico " + ResourceNames.TOPIC_RULE) String name) {
        service.deleteTopic(name);
        return ResponseEntity.noContent().build();
    }
}
