package cl.duoc.pedidos360.kafkaadmin.controller;

import cl.duoc.pedidos360.kafkaadmin.dto.ApiErrorResponse;
import cl.duoc.pedidos360.kafkaadmin.dto.ClusterResponse;
import cl.duoc.pedidos360.kafkaadmin.service.KafkaAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kafka-admin/cluster")
@Tag(name = "Clúster", description = "Brokers y controlador del clúster Kafka")
public class ClusterAdminController {
    private final KafkaAdminService service;

    public ClusterAdminController(KafkaAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Identificador del clúster, controlador y brokers activos")
    @ApiResponse(responseCode = "200", description = "Estado del clúster")
    @ApiResponse(responseCode = "503", description = "Kafka no disponible", content = @Content(
            schema = @Schema(implementation = ApiErrorResponse.class)))
    public ClusterResponse cluster() {
        return service.cluster();
    }
}
