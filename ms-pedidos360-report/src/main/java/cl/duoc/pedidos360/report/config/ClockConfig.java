package cl.duoc.pedidos360.report.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {
    @Bean
    Clock reportClock() {
        return Clock.systemUTC();
    }
}
