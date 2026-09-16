package cl.duoc.pedidos360.orders.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class CatalogClientConfig {
    @Bean
    RestClient catalogRestClient(RestClient.Builder builder,
            @Value("${orders.catalog.base-url}") String baseUrl,
            @Value("${orders.catalog.connect-timeout}") Duration connectTimeout,
            @Value("${orders.catalog.read-timeout}") Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
