package cl.duoc.pedidos360.report.config;

import cl.duoc.pedidos360.report.exception.InvalidEventException;
import cl.duoc.pedidos360.report.messaging.KafkaFailures;
import cl.duoc.pedidos360.report.messaging.RejectedEventRecoverer;
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
 * Manejo de errores de los dos consumidores de Report (orders.events y audit.timeline):
 * <pre>
 * | Situación                                                        | Decisión                                            |
 * |------------------------------------------------------------------|-----------------------------------------------------|
 * | Payload que no cumple el contrato (DeserializationException)     | sin reintentos -> report_rejections + &lt;tópico&gt;.report.DLT |
 * | Mensaje válido pero imposible de proyectar (InvalidEventException)| sin reintentos -> report_rejections + DLT          |
 * | Base de datos no disponible (transitorio)                        | reintentos 1 s, 2 s, 4 s... hasta 30 s, sin límite  |
 * | Otro error inesperado                                            | 3 reintentos (1 s, 2 s, 4 s) -> report_rejections + DLT |
 * </pre>
 * Cada reintento y cada rechazo quedan en el log con tópico, partición, offset y clave.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Publica el mensaje original en &lt;tópico de origen&gt;.report.DLT, en la misma partición. */
    @Bean
    DeadLetterPublishingRecoverer reportDeadLetterPublisher(KafkaTemplate<String, Object> kafka,
            @Value("${report.dead-letter-suffix}") String deadLetterSuffix) {
        return new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + deadLetterSuffix, record.partition()));
    }

    @Bean
    DefaultErrorHandler reportErrorHandler(RejectedEventRecoverer recoverer,
            @Value("${report.events.retry.max-retries:3}") int maxRetries,
            @Value("${report.events.retry.initial-interval-ms:1000}") long initialInterval,
            @Value("${report.events.retry.max-interval-ms:30000}") long maxInterval) {
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

    /** Falla transitoria: backoff exponencial sin límite de intentos; para lo demás, el backoff por defecto. */
    static BackOff backOffFor(Exception exception, long initialInterval, long maxInterval) {
        if (!KafkaFailures.isTransient(exception)) return null;
        var transientFailure = new ExponentialBackOff(initialInterval, 2.0);
        transientFailure.setMaxInterval(maxInterval);
        return transientFailure;
    }
}
