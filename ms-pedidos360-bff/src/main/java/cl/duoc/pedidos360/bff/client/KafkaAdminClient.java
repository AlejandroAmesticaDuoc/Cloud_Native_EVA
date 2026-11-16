package cl.duoc.pedidos360.bff.client;

import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.function.Supplier;

@Component
public class KafkaAdminClient {

    private final RestClient restClient;

    public KafkaAdminClient(
            RestClient.Builder builder,
            DownstreamRequestInterceptor interceptor,
            @Value("${app.clients.kafka-admin.base-url:http://kafka-admin:8087}") String baseUrl) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestInterceptor(interceptor)
                .build();
    }

    public Object listTopics() {
        return execute(() -> restClient.get()
                .uri("/api/v1/kafka-admin/topics")
                .retrieve()
                .body(Object.class));
    }

    public Object createTopic(Object body) {
        return execute(() -> restClient.post()
                .uri("/api/v1/kafka-admin/topics")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Object.class));
    }

    public Object updateTopic(String name, Object body) {
        return execute(() -> restClient.patch()
                .uri("/api/v1/kafka-admin/topics/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Object.class));
    }

    public Object deleteTopic(String name) {
        return execute(() -> restClient.delete()
                .uri("/api/v1/kafka-admin/topics/{name}", name)
                .retrieve()
                .body(Object.class));
    }

    public Object listConsumerGroups() {
        return execute(() -> restClient.get()
                .uri("/api/v1/kafka-admin/consumer-groups")
                .retrieve()
                .body(Object.class));
    }

    public Object publishMessage(Object body) {
        return execute(() -> restClient.post()
                .uri("/api/v1/kafka-admin/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Object.class));
    }

    private <T> T execute(Supplier<T> op) {
        try {
            return op.get();
        } catch (RestClientException ex) {
            throw new DownstreamServiceException("Error comunicando con kafka-admin: " + ex.getMessage(), ex);
        }
    }
}
