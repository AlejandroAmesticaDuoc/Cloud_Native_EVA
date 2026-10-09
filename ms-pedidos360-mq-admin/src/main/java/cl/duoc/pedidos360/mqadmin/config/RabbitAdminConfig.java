package cl.duoc.pedidos360.mqadmin.config;

import com.rabbitmq.client.ShutdownSignalException;
import org.apache.commons.logging.Log;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Infraestructura de administración de RabbitMQ. Toda la configuración técnica (RabbitAdmin y
 * cliente HTTP de management) vive aquí; los controladores nunca la ven.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MqAdminProperties.class)
public class RabbitAdminConfig {

    /**
     * RabbitAdmin solo para declaraciones explícitas pedidas por la API: mq-admin no declara
     * topología propia al conectarse y no vuelve a declarar lo que se eliminó.
     */
    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(false);
        admin.setExplicitDeclarationsOnly(true);
        admin.setRedeclareManualDeclarations(false);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }

    /**
     * En mq-admin un cierre de canal por 404/405/406 es una respuesta esperada del broker (la API la
     * informa como 404/409): se registra en WARN y no en ERROR, que queda para fallas de conexión.
     */
    @Bean
    static BeanPostProcessor channelCloseLogging() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractConnectionFactory factory) {
                    factory.setCloseExceptionLogger(RabbitAdminConfig::logChannelClose);
                }
                return bean;
            }
        };
    }

    static void logChannelClose(Log logger, String message, Throwable cause) {
        if (!(cause instanceof ShutdownSignalException signal)) {
            logger.error(message + ": " + cause);
        } else if (RabbitUtils.isNormalChannelClose(signal) || RabbitUtils.isPassiveDeclarationChannelClose(signal)) {
            logger.debug(message + ": " + signal.getMessage());
        } else if (!signal.isHardError()) {
            logger.warn("Operación rechazada por RabbitMQ: " + signal.getMessage());
        } else {
            logger.error(message + ": " + signal.getMessage());
        }
    }

    /** Cliente de la API HTTP de management con autenticación básica y tiempos acotados. */
    @Bean
    RestClient rabbitManagementRestClient(RestClient.Builder builder, MqAdminProperties properties,
            RabbitProperties rabbit) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.managementTimeout());
        factory.setReadTimeout(properties.managementTimeout());
        return builder.baseUrl(properties.managementUrl().toString())
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setBasicAuth(rabbit.determineUsername(), rabbit.determinePassword()))
                .build();
    }
}
