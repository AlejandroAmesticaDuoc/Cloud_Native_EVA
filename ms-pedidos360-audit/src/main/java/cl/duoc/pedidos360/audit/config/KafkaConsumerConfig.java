package cl.duoc.pedidos360.audit.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {
    @Bean
    DefaultErrorHandler auditErrorHandler() {
        var handler = new DefaultErrorHandler((record, exception) -> {
            throw new IllegalStateException("No se pudo persistir el evento de auditoría", exception);
        }, new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(Exception.class, true), true);
        return handler;
    }
}
