package cl.duoc.pedidos360.report.messaging;

import java.nio.charset.StandardCharsets;
import cl.duoc.pedidos360.report.dto.OrderEvent;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.kafka.common.serialization.Deserializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deserializer del valor de orders.events al DTO {@link OrderEvent} con la validación estricta del contrato v1.
 * Se configura como delegado de ErrorHandlingDeserializer (application.properties): si el payload es inválido,
 * el listener no se invoca y el error llega al DefaultErrorHandler como DeserializationException.
 */
public class OrderEventDeserializer implements Deserializer<OrderEvent> {
    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final EventParser parser = new EventParser(JsonMapper.builder().findAndAddModules().build(),
            validation.getValidator());

    @Override
    public OrderEvent deserialize(String topic, byte[] data) {
        if (data == null) throw new IllegalArgumentException("INVALID_EVENT");
        return parser.parse(new String(data, StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        validation.close();
    }
}
