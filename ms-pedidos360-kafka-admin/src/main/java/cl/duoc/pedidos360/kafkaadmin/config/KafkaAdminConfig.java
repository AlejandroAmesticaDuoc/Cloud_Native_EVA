package cl.duoc.pedidos360.kafkaadmin.config;

import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cliente {@link Admin} de Kafka construido con spring.kafka.bootstrap-servers y spring.kafka.admin.*.
 * Lo usa solo KafkaAdminServiceImpl; los controladores no conocen el cliente.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaAdminConfig {

    @Bean(destroyMethod = "close")
    Admin kafkaAdminClient(KafkaProperties properties) {
        return Admin.create(properties.buildAdminProperties());
    }

    /** Readiness: el clúster responde describeCluster dentro del timeout. */
    @Bean
    HealthIndicator kafkaCluster(Admin admin) {
        return () -> {
            try {
                var cluster = admin.describeCluster(new DescribeClusterOptions().timeoutMs(3000));
                int brokers = cluster.nodes().get(3, TimeUnit.SECONDS).size();
                return brokers > 0 ? Health.up().build() : Health.down().build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Health.down().build();
            } catch (Exception exception) {
                return Health.down().build();
            }
        };
    }
}
