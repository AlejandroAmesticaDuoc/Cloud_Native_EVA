package cl.duoc.pedidos360.mqadmin.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Validación de clase: combina campos de {@link CreateQueueRequest} que no se validan por separado. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = QueueSettingsValidator.class)
public @interface ValidQueueSettings {
    String message() default "La configuración de la cola es inválida";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
