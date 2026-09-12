package cl.duoc.pedidos360.bff.config;

import java.net.URI;

import cl.duoc.pedidos360.bff.client.DownstreamRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class RestClientConfig {

    @Bean
    public RestClient ordersRestClient(
            RestClient.Builder builder,
            DownstreamRequestInterceptor interceptor,
            @Value("${app.clients.orders.base-url}")
            URI baseUrl) {

        return buildClient(
                builder,
                interceptor,
                baseUrl
        );
    }

    @Bean
    public RestClient catalogRestClient(
            RestClient.Builder builder,
            DownstreamRequestInterceptor interceptor,
            @Value("${app.clients.catalog.base-url}")
            URI baseUrl) {

        return buildClient(
                builder,
                interceptor,
                baseUrl
        );
    }

    @Bean
    public RestClient auditRestClient(RestClient.Builder builder,
            DownstreamRequestInterceptor interceptor,
            @Value("${app.clients.audit.base-url}") URI baseUrl) {
        return buildClient(builder, interceptor, baseUrl);
    }

    private RestClient buildClient(
            RestClient.Builder builder,
            DownstreamRequestInterceptor interceptor,
            URI baseUrl) {

        return builder
                .baseUrl(baseUrl)
                .defaultHeader(
                        HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .requestInterceptor(interceptor)
                .build();
    }
}
