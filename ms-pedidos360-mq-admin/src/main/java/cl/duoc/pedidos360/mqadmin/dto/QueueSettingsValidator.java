package cl.duoc.pedidos360.mqadmin.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Reglas entre campos: una cola QUORUM siempre es durable y no se autoelimina, la routing key de
 * dead letter solo tiene sentido si se indica el exchange de dead letter y RabbitMQ 4.x ya no
 * permite colas clásicas transitorias no exclusivas (deprecated feature {@code transient_nonexcl_queues}).
 */
public class QueueSettingsValidator implements ConstraintValidator<ValidQueueSettings, CreateQueueRequest> {

    @Override
    public boolean isValid(CreateQueueRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        if (!request.durable()) {
            valid = reject(context, "durable", request.type() == QueueType.QUORUM
                    ? "Las colas QUORUM deben ser durables (durable=true)"
                    : "RabbitMQ 4.x no admite colas transitorias (durable=false); usa durable=true");
        }
        if (request.type() == QueueType.QUORUM && request.autoDelete()) {
            valid = reject(context, "autoDelete", "Las colas QUORUM no admiten autoDelete=true");
        }
        if (request.deadLetterRoutingKey() != null && request.deadLetterExchange() == null) {
            valid = reject(context, "deadLetterRoutingKey",
                    "deadLetterRoutingKey requiere indicar también deadLetterExchange");
        }
        return valid;
    }

    private static boolean reject(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
