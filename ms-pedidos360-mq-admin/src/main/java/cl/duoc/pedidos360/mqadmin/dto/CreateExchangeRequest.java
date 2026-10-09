package cl.duoc.pedidos360.mqadmin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Solicitud para crear un exchange; el tipo es obligatorio. */
public record CreateExchangeRequest(
        @Schema(example = "ex.demo.ep3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El nombre del exchange es obligatorio")
        @Pattern(regexp = ResourceNames.NAME, message = "El nombre del exchange " + ResourceNames.NAME_RULE)
        String name,

        @Schema(example = "TOPIC", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "El tipo de exchange es obligatorio (DIRECT, TOPIC, FANOUT o HEADERS)")
        ExchangeType type,

        @Schema(defaultValue = "true")
        Boolean durable,

        @Schema(defaultValue = "false")
        Boolean autoDelete) {

    public CreateExchangeRequest {
        durable = durable == null ? Boolean.TRUE : durable;
        autoDelete = autoDelete == null ? Boolean.FALSE : autoDelete;
    }
}
