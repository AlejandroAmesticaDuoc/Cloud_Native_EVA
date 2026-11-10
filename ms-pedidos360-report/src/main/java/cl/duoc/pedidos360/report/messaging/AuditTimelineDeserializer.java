package cl.duoc.pedidos360.report.messaging;

import cl.duoc.pedidos360.report.dto.AuditTimelineEntry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.kafka.common.serialization.Deserializer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deserializer del valor de audit.timeline al DTO {@link AuditTimelineEntry}: JSON estricto (sin campos
 * desconocidos ni faltantes, sin coerción de tipos) más Bean Validation. Delegado de ErrorHandlingDeserializer
 * en el listener de la línea de tiempo.
 */
public class AuditTimelineDeserializer implements Deserializer<AuditTimelineEntry> {
    private static final int MAX_BYTES = 16384;
    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final JsonMapper json = JsonMapper.builder().findAndAddModules()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    @Override
    public AuditTimelineEntry deserialize(String topic, byte[] data) {
        if (data == null || data.length > MAX_BYTES) throw new IllegalArgumentException("INVALID_EVENT");
        AuditTimelineEntry entry = json.readValue(data, AuditTimelineEntry.class);
        if (entry == null || !validation.getValidator().validate(entry).isEmpty()) {
            throw new IllegalArgumentException("INVALID_EVENT");
        }
        return entry;
    }

    @Override
    public void close() {
        validation.close();
    }
}
