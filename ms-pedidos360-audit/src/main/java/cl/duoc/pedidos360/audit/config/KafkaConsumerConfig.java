package cl.duoc.pedidos360.audit.config;

import cl.duoc.pedidos360.audit.exception.InvalidEventException;
import cl.duoc.pedidos360.audit.messaging.KafkaFailures;
import cl.duoc.pedidos360.audit.messaging.RejectedEventRecoverer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Manejo de errores del consumidor de orders.events (lo aplica el contenedor de @KafkaListener):
 * <pre>
 * | Situación                                                        | Decisión                                              |
 * |------------------------------------------------------------------|-------------------------------------------------------|
 * | Payload que no cumple el contrato v1 (DeserializationException)  | sin reintentos -> audit_rejections + orders.events.audit.DLT |
 * | Evento válido pero imposible de registrar (InvalidEventException)| sin reintentos -> audit_rejections + DLT              |
 * | Base de datos o Kafka no disponibles (transitorio)               | reintentos 1 s, 2 s, 4 s... hasta 30 s, sin límite: no se pierde |
 * | Otro error inesperado                                            | 3 reintentos (1 s, 2 s, 4 s) -> audit_rejections + DLT |
 * </pre>
 * Cada reintento y cada rechazo quedan en el log con tópico, partición, offset y clave. El offset se confirma
 * recién cuando el mensaje se procesó o se envió a la DLT (ack-mode=record), así ningún mensaje defectuoso
 * detiene el consumo de los demás.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Publica el mensaje original en orders.events.audit.DLT, en la misma partición de origen. */
    @Bean
    DeadLetterPublishingRecoverer auditDeadLetterPublisher(KafkaTemplate<String, Object> kafka,
            @Value("${audit.events.dead-letter-topic}") String deadLetterTopic) {
        return new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
    }

    @Bean
    DefaultErrorHandler auditErrorHandler(RejectedEventRecoverer recoverer,
            @Value("${audit.events.retry.max-retries:3}") int maxRetries,
            @Value("${audit.events.retry.initial-interval-ms:1000}") long initialInterval,
            @Value("${audit.events.retry.max-interval-ms:30000}") long maxInterval) {
        var unexpected = new ExponentialBackOffWithMaxRetries(maxRetries);
        unexpected.setInitialInterval(initialInterval);
        unexpected.setMultiplier(2.0);
        unexpected.setMaxInterval(4 * initialInterval);
        var handler = new DefaultErrorHandler(recoverer, unexpected);
        // DeserializationException ya es no reintentable por defecto en DefaultErrorHandler.
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setBackOffFunction((record, exception) -> backOffFor(exception, initialInterval, maxInterval));
        handler.setRetryListeners((record, exception, attempt) -> log.warn(
                "[RETRY] Intento {} fallido topic={} partition={} offset={} key={} transitorio={} causa={}", attempt,
                record.topic(), record.partition(), record.offset(), record.key(), KafkaFailures.isTransient(exception),
                KafkaFailures.describe(exception)));
        return handler;
    }

    /**
     * Falla transitoria (base de datos o Kafka): backoff exponencial sin límite de intentos, para no perder ni
     * saltar el evento mientras la infraestructura se recupera. Para lo demás devuelve null (backoff por defecto).
     */
    static BackOff backOffFor(Exception exception, long initialInterval, long maxInterval) {
        if (!KafkaFailures.isTransient(exception)) return null;
        var transientFailure = new ExponentialBackOff(initialInterval, 2.0);
        transientFailure.setMaxInterval(maxInterval);
        return transientFailure;
    }
}
