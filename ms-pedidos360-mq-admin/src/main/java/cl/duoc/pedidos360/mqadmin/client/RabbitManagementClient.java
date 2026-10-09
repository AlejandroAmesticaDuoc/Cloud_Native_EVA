package cl.duoc.pedidos360.mqadmin.client;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import cl.duoc.pedidos360.mqadmin.exception.BrokerUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Lecturas sobre la API HTTP de management de RabbitMQ (listados, existencia y tipo de recursos).
 * Las mutaciones se hacen por AMQP con RabbitAdmin. El vhost y los nombres se envían como variables
 * de plantilla, por lo que RestClient los codifica (por ejemplo, el vhost "/" viaja como %2F).
 */
@Component
public class RabbitManagementClient {
    private static final ParameterizedTypeReference<List<ManagementQueue>> QUEUES = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<ManagementExchange>> EXCHANGES = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<ManagementBinding>> BINDINGS = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final String vhost;

    public RabbitManagementClient(@Qualifier("rabbitManagementRestClient") RestClient restClient,
            RabbitProperties rabbit) {
        this.restClient = restClient;
        this.vhost = rabbit.determineVirtualHost();
    }

    public List<ManagementQueue> listQueues() {
        return list(() -> restClient.get().uri("/api/queues/{vhost}", vhost).retrieve().body(QUEUES));
    }

    public Optional<ManagementQueue> findQueue(String name) {
        return find(() -> restClient.get().uri("/api/queues/{vhost}/{name}", vhost, name)
                .retrieve().body(ManagementQueue.class));
    }

    public List<ManagementExchange> listExchanges() {
        return list(() -> restClient.get().uri("/api/exchanges/{vhost}", vhost).retrieve().body(EXCHANGES));
    }

    public Optional<ManagementExchange> findExchange(String name) {
        return find(() -> restClient.get().uri("/api/exchanges/{vhost}/{name}", vhost, name)
                .retrieve().body(ManagementExchange.class));
    }

    public List<ManagementBinding> listBindings() {
        return list(() -> restClient.get().uri("/api/bindings/{vhost}", vhost).retrieve().body(BINDINGS));
    }

    /** Bindings cuyo origen es el exchange indicado. */
    public List<ManagementBinding> bindingsFromExchange(String exchange) {
        return list(() -> restClient.get().uri("/api/exchanges/{vhost}/{name}/bindings/source", vhost, exchange)
                .retrieve().body(BINDINGS));
    }

    /** Bindings que llegan a la cola indicada (incluye el implícito del exchange por defecto). */
    public List<ManagementBinding> bindingsToQueue(String queue) {
        return list(() -> restClient.get().uri("/api/queues/{vhost}/{name}/bindings", vhost, queue)
                .retrieve().body(BINDINGS));
    }

    /** Bindings entre un exchange y una cola. */
    public List<ManagementBinding> bindingsBetween(String exchange, String queue) {
        return list(() -> restClient.get().uri("/api/bindings/{vhost}/e/{exchange}/q/{queue}", vhost, exchange, queue)
                .retrieve().body(BINDINGS));
    }

    private <T> List<T> list(Supplier<List<T>> request) {
        return find(request).orElse(List.of());
    }

    private <T> Optional<T> find(Supplier<T> request) {
        try {
            return Optional.ofNullable(request.get());
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        } catch (RestClientResponseException response) {
            throw new BrokerUnavailableException("La API de management de RabbitMQ respondió HTTP "
                    + response.getStatusCode().value() + "; revisa RABBITMQ_MANAGEMENT_URL y las credenciales", response);
        } catch (RestClientException unavailable) {
            throw new BrokerUnavailableException("La API de management de RabbitMQ no está disponible", unavailable);
        }
    }
}
