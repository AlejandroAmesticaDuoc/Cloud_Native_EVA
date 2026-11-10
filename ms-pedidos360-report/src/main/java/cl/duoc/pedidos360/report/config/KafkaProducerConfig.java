package cl.duoc.pedidos360.report.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Productor de Report para sus DLT (orders.events.report.DLT y audit.timeline.report.DLT). Usa las propiedades
 * spring.kafka.producer.* (acks=all, idempotencia, timeouts) y un serializer por tipo: byte[] se publica tal cual
 * (payload original que no se pudo deserializar) y cualquier DTO va como JSON sin headers de tipo Java.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, Object> kafkaProducerFactory(KafkaProperties properties) {
        Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();
        serializers.put(byte[].class, new ByteArraySerializer());
        serializers.put(Object.class, new JacksonJsonSerializer<>().noTypeInfo());
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(), new StringSerializer(),
                new DelegatingByTypeSerializer(serializers, true));
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }
}
