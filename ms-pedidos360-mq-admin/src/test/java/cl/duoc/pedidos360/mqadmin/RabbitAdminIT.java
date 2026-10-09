package cl.duoc.pedidos360.mqadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Integración contra un RabbitMQ real (la misma imagen del compose). Usa el vhost "/" para comprobar
 * además que el cliente de management lo codifica como %2F.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RabbitAdminIT {
    private static final String API = "/api/v1/mq-admin";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.5-management-alpine")
            .withAdminUser("it-admin")
            .withAdminPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("RABBITMQ_HOST", RABBIT::getHost);
        registry.add("RABBITMQ_PORT", RABBIT::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", RABBIT::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", RABBIT::getAdminPassword);
        registry.add("RABBITMQ_VHOST", () -> "/");
        registry.add("RABBITMQ_MANAGEMENT_URL", RABBIT::getHttpUrl);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static RequestPostProcessor adminToken() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_pedidos360.access"),
                new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(adminToken()));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return perform(post(API + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long depth(String queue) {
        QueueInformation info = admin.getQueueInfo(queue);
        return info == null ? -1 : info.getMessageCount();
    }

    @Test
    void createsBindsPurgesAndDeletesRealResources() throws Exception {
        postJson("/exchanges", "{\"name\":\"it.ex.topic\",\"type\":\"TOPIC\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(API + "/exchanges/it.ex.topic")));
        postJson("/exchanges", "{\"name\":\"it.ex.topic\",\"type\":\"TOPIC\"}").andExpect(status().isConflict());
        postJson("/exchanges", "{\"name\":\"it.ex.direct\",\"type\":\"DIRECT\"}").andExpect(status().isCreated());

        postJson("/queues", "{\"name\":\"it.q.demo\",\"maxLength\":100,\"messageTtlMs\":60000}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(API + "/queues/it.q.demo")));
        postJson("/queues", "{\"name\":\"it.q.demo\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La cola 'it.q.demo' ya existe"));
        perform(get(API + "/queues/it.q.demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CLASSIC"))
                .andExpect(jsonPath("$.arguments['x-max-length']").value(100))
                .andExpect(jsonPath("$.arguments['x-message-ttl']").value(60000));
        postJson("/queues", "{\"name\":\"it.q.quorum\",\"type\":\"QUORUM\",\"deadLetterExchange\":\"it.ex.direct\","
                + "\"deadLetterRoutingKey\":\"demo.failed\"}").andExpect(status().isCreated());
        perform(get(API + "/queues/it.q.quorum")).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("QUORUM"));
        postJson("/queues", "{\"name\":\"it.q.bad\",\"deadLetterExchange\":\"it.ex.none\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("'it.ex.none' no existe")));

        String binding = "{\"exchange\":\"it.ex.topic\",\"queue\":\"it.q.demo\",\"routingKey\":\"demo.#\"}";
        postJson("/bindings", binding).andExpect(status().isCreated());
        postJson("/bindings", binding).andExpect(status().isConflict());
        postJson("/bindings", "{\"exchange\":\"it.ex.direct\",\"queue\":\"it.q.demo\",\"routingKey\":\"demo.*\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("solo se permiten en exchanges topic")));
        postJson("/bindings", "{\"exchange\":\"it.ex.none\",\"queue\":\"it.q.demo\",\"routingKey\":\"a\"}")
                .andExpect(status().isNotFound());
        perform(get(API + "/bindings").param("exchange", "it.ex.topic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].queue").value("it.q.demo"))
                .andExpect(jsonPath("$[0].routingKey").value("demo.#"));

        for (int index = 0; index < 3; index++) {
            rabbitTemplate.convertAndSend("it.ex.topic", "demo.created", "mensaje-" + index);
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> depth("it.q.demo") == 3);
        perform(delete(API + "/queues/it.q.demo").param("ifEmpty", "true")).andExpect(status().isConflict());
        perform(delete(API + "/queues/it.q.demo/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(3));
        assertThat(depth("it.q.demo")).isZero();

        perform(delete(API + "/bindings").param("exchange", "it.ex.topic").param("queue", "it.q.demo")
                .param("routingKey", "demo.#")).andExpect(status().isNoContent());
        perform(delete(API + "/bindings").param("exchange", "it.ex.topic").param("queue", "it.q.demo")
                .param("routingKey", "demo.#")).andExpect(status().isNotFound());

        postJson("/bindings", "{\"exchange\":\"it.ex.direct\",\"queue\":\"it.q.demo\",\"routingKey\":\"demo.direct\"}")
                .andExpect(status().isCreated());
        perform(delete(API + "/exchanges/it.ex.direct").param("ifUnused", "true")).andExpect(status().isConflict());
        perform(delete(API + "/queues/it.q.quorum")).andExpect(status().isNoContent());
        perform(delete(API + "/exchanges/it.ex.direct")).andExpect(status().isNoContent());
        perform(delete(API + "/exchanges/it.ex.direct")).andExpect(status().isNotFound());

        perform(delete(API + "/queues/it.q.demo")).andExpect(status().isNoContent());
        perform(get(API + "/queues/it.q.demo")).andExpect(status().isNotFound());
        perform(delete(API + "/queues/it.q.demo")).andExpect(status().isNotFound());
        perform(delete(API + "/exchanges/it.ex.topic").param("ifUnused", "true")).andExpect(status().isNoContent());
        assertThat(depth("it.q.demo")).isEqualTo(-1);
    }

    @Test
    void replaysDeadLettersToTheOriginalExchangeAndProtectsTheBaseTopology() throws Exception {
        DirectExchange direct = ExchangeBuilder.directExchange("cmd.direct").durable(true).build();
        DirectExchange deadLetter = ExchangeBuilder.directExchange("cmd.dead.dlx").durable(true).build();
        Queue email = QueueBuilder.durable("q.cmd.email").deadLetterExchange("cmd.dead.dlx")
                .deadLetterRoutingKey("email.send").maxLength(10_000L).build();
        Queue emailDlq = QueueBuilder.durable("q.cmd.email.dlq").ttl(604_800_000).maxLength(10_000L).build();
        admin.declareExchange(direct);
        admin.declareExchange(deadLetter);
        admin.declareQueue(email);
        admin.declareQueue(emailDlq);
        admin.declareBinding(BindingBuilder.bind(email).to(direct).with("email.send"));
        admin.declareBinding(BindingBuilder.bind(emailDlq).to(deadLetter).with("email.send"));

        String payload = "{\"schemaVersion\":1,\"eventId\":\"evt-it-1\"}";
        Message command = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setMessageId("evt-it-1").setHeader("x-trace-id", "trace-it").build();
        rabbitTemplate.send("cmd.direct", "email.send", command);
        // Un consumidor rechaza el mensaje sin reencolar: RabbitMQ lo envía por el DLX a la DLQ.
        rabbitTemplate.execute(channel -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            GetResponse response = channel.basicGet("q.cmd.email", false);
            while (response == null && System.nanoTime() < deadline) {
                Thread.sleep(100);
                response = channel.basicGet("q.cmd.email", false);
            }
            assertThat(response).isNotNull();
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });
        // Un mensaje sin x-death no tiene destino original conocido: debe quedarse en la DLQ.
        rabbitTemplate.send("", "q.cmd.email.dlq", MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setMessageId("sin-origen").build());
        await().atMost(Duration.ofSeconds(10)).until(() -> depth("q.cmd.email.dlq") == 2);

        postJson("/dead-letter-queues/q.cmd.email.dlq/replay", "{\"maxMessages\":10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.failed").value(1));

        Message replayed = rabbitTemplate.receive("q.cmd.email", 5000);
        assertThat(replayed).isNotNull();
        assertThat(new String(replayed.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
        assertThat(replayed.getMessageProperties().getMessageId()).isEqualTo("evt-it-1");
        assertThat(replayed.getMessageProperties().getHeaders())
                .containsEntry("x-trace-id", "trace-it")
                .containsEntry("x-replayed-from", "q.cmd.email.dlq")
                .doesNotContainKey("x-death");
        assertThat(depth("q.cmd.email.dlq")).isEqualTo(1);

        perform(get(API + "/dead-letter-queues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].queue").value("q.cmd.email.dlq"))
                .andExpect(jsonPath("$[0].exists").value(true))
                .andExpect(jsonPath("$[0].threshold").value(1))
                .andExpect(jsonPath("$[1].exists").value(false));
        postJson("/dead-letter-queues/q.cmd.email/replay", "{\"maxMessages\":1}").andExpect(status().isNotFound());
        perform(delete(API + "/queues/q.cmd.email.dlq"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("topología base")));
        perform(delete(API + "/exchanges/cmd.dead.dlx")).andExpect(status().isConflict());
        postJson("/queues", "{\"name\":\"q.cmd.kitchen\"}").andExpect(status().isConflict());
        perform(delete(API + "/queues/q.cmd.email.dlq/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(1));
    }
}
