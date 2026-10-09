package cl.duoc.pedidos360.mqadmin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Binding entre un exchange y una cola. Los comodines '*' y '#' solo se aceptan en exchanges topic. */
public record BindingRequest(
        @Schema(example = "ex.demo.ep3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El exchange es obligatorio")
        @Pattern(regexp = ResourceNames.NAME, message = "El nombre del exchange " + ResourceNames.NAME_RULE)
        String exchange,

        @Schema(example = "q.demo.ep3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "La cola es obligatoria")
        @Pattern(regexp = ResourceNames.NAME, message = "El nombre de la cola " + ResourceNames.NAME_RULE)
        String queue,

        @Schema(example = "demo.#", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Clave de binding; vacía solo tiene sentido en exchanges fanout o headers")
        @NotNull(message = "La routingKey es obligatoria (vacía solo para exchanges fanout o headers)")
        @Size(max = 255, message = "La routingKey no puede superar 255 caracteres")
        @Pattern(regexp = ResourceNames.BINDING_KEY, message = "La routingKey " + ResourceNames.BINDING_KEY_RULE)
        String routingKey) {
}
