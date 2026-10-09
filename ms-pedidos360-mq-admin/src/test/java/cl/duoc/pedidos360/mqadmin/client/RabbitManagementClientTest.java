package cl.duoc.pedidos360.mqadmin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;

import cl.duoc.pedidos360.mqadmin.exception.BrokerUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RabbitManagementClientTest {
    private MockRestServiceServer server;
    private RabbitManagementClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://rabbit:15672")
                .defaultHeaders(headers -> headers.setBasicAuth("admin", "secreto-de-prueba"));
        server = MockRestServiceServer.bindTo(builder).build();
        RabbitProperties rabbit = new RabbitProperties();
        rabbit.setVirtualHost("/");
        client = new RabbitManagementClient(builder.build(), rabbit);
    }

    @Test
    void encodesTheVhostAndIgnoresUnknownFields() {
        server.expect(requestTo(URI.create("http://rabbit:15672/api/queues/%2F/q.cmd.email.dlq")))
                .andExpect(header("Authorization", "Basic YWRtaW46c2VjcmV0by1kZS1wcnVlYmE="))
                .andRespond(withSuccess("""
                        {"name":"q.cmd.email.dlq","type":"classic","durable":true,"auto_delete":false,
                         "arguments":{"x-message-ttl":604800000},"messages":2,"messages_ready":2,
                         "messages_unacknowledged":0,"consumers":0,"state":"running","node":"rabbit@x"}
                        """, MediaType.APPLICATION_JSON));
        ManagementQueue queue = client.findQueue("q.cmd.email.dlq").orElseThrow();
        assertThat(queue.messages()).isEqualTo(2);
        assertThat(queue.arguments()).containsEntry("x-message-ttl", 604800000);
        server.verify();
    }

    @Test
    void mapsNotFoundToEmpty() {
        server.expect(requestTo(URI.create("http://rabbit:15672/api/exchanges/%2F/ex.none")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.findExchange("ex.none")).isEmpty();
    }

    @Test
    void mapsAuthenticationAndConnectionErrorsToUnavailable() {
        server.expect(requestTo(URI.create("http://rabbit:15672/api/queues/%2F")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(URI.create("http://rabbit:15672/api/bindings/%2F")))
                .andRespond(withException(new IOException("Connection refused")));
        assertThatThrownBy(client::listQueues).isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("HTTP 401");
        assertThatThrownBy(client::listBindings).isInstanceOf(BrokerUnavailableException.class)
                .hasMessage("La API de management de RabbitMQ no está disponible");
    }

    @Test
    void readsBindingsBetweenExchangeAndQueue() {
        server.expect(requestTo(URI.create("http://rabbit:15672/api/bindings/%2F/e/cmd.direct/q/q.cmd.email")))
                .andRespond(withSuccess("""
                        [{"source":"cmd.direct","vhost":"/","destination":"q.cmd.email","destination_type":"queue",
                          "routing_key":"email.send","arguments":{},"properties_key":"email.send"}]
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.bindingsBetween("cmd.direct", "q.cmd.email")).singleElement()
                .satisfies(binding -> {
                    assertThat(binding.routingKey()).isEqualTo("email.send");
                    assertThat(binding.targetsQueue()).isTrue();
                });
    }
}
