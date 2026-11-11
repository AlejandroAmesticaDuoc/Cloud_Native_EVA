package cl.duoc.pedidos360.kafkaadmin.dto;

import java.util.Map;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cambios de configuración de un tópico: {@code set} fija valores y {@code reset} vuelve claves al valor por
 * defecto del broker. Se aplican de forma incremental (las demás configuraciones no cambian).
 */
public record UpdateTopicConfigsRequest(
        @Schema(example = "{\"retention.ms\":\"259200000\"}")
        @Size(max = 20, message = "set admite como máximo 20 configuraciones")
        Map<@NotBlank(message = "La clave de configuración no puede estar vacía") String,
            @NotBlank(message = "El valor de configuración no puede estar vacío")
            @Size(max = 100, message = "El valor de configuración no puede superar 100 caracteres") String> set,

        @Schema(example = "[\"segment.ms\"]")
        @Size(max = 20, message = "reset admite como máximo 20 configuraciones")
        Set<@NotBlank(message = "La clave de configuración no puede estar vacía") String> reset) {

    public UpdateTopicConfigsRequest {
        set = set == null ? Map.of() : Map.copyOf(set);
        reset = reset == null ? Set.of() : Set.copyOf(reset);
    }

    @Schema(hidden = true)
    @AssertTrue(message = "Indica al menos una configuración en set o reset")
    public boolean isNotEmpty() {
        return !set.isEmpty() || !reset.isEmpty();
    }

    @Schema(hidden = true)
    @AssertTrue(message = "Una misma configuración no puede estar en set y en reset")
    public boolean isWithoutOverlap() {
        return reset.stream().noneMatch(set::containsKey);
    }
}
