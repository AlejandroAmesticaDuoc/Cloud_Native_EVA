package cl.duoc.pedidos360.mqadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import cl.duoc.pedidos360.mqadmin.client.ManagementBinding;
import cl.duoc.pedidos360.mqadmin.client.ManagementExchange;
import cl.duoc.pedidos360.mqadmin.client.ManagementQueue;
import cl.duoc.pedidos360.mqadmin.client.RabbitManagementClient;
import cl.duoc.pedidos360.mqadmin.config.MqAdminProperties;
import cl.duoc.pedidos360.mqadmin.dto.BindingRequest;
import cl.duoc.pedidos360.mqadmin.dto.CreateExchangeRequest;
import cl.duoc.pedidos360.mqadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.mqadmin.dto.DeadLetterQueueStatus;
import cl.duoc.pedidos360.mqadmin.dto.ExchangeType;
import cl.duoc.pedidos360.mqadmin.dto.QueueResponse;
import cl.duoc.pedidos360.mqadmin.dto.QueueType;
import cl.duoc.pedidos360.mqadmin.dto.ReplayResponse;
import cl.duoc.pedidos360.mqadmin.exception.BrokerUnavailableException;
import cl.duoc.pedidos360.mqadmin.exception.InvalidAdminRequestException;
import cl.duoc.pedidos360.mqadmin.exception.ProtectedResourceException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceNotFoundException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitOperations;

class RabbitAdminServiceImplTest {
    private static final String EMAIL_DLQ = "q.cmd.email.dlq";

    private AmqpAdmin amqpAdmin;
    private RabbitOperations rabbit;
    private RabbitManagementClient management;
    private Channel channel;
    private RabbitAdminServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        amqpAdmin = mock(AmqpAdmin.class);
        rabbit = mock(RabbitOperations.class);
        management = mock(RabbitManagementClient.class);
        channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.addReturnListener(any(ReturnCallback.class))).thenReturn(mock(ReturnListener.class));
        when(rabbit.execute(any())).thenAnswer(invocation ->
                invocation.<ChannelCallback<?>>getArgument(0).doInRabbit(channel));
        MqAdminProperties properties = new MqAdminProperties(URI.create("http://localhost:15672"),
                Duration.ofSeconds(3), Duration.ofSeconds(5), "cmd.dead.dlx",
                Set.of("cmd.direct", "cmd.topic", "cmd.dead.dlx", "q.cmd.email", EMAIL_DLQ,
                        "q.audit.dead-letters", "q.audit.dead-letters.dlq"),
                new MqAdminProperties.Alerts(List.of(EMAIL_DLQ, "q.cmd.kitchen.dlq", "q.audit.dead-letters.dlq"),
                        2, Duration.ofSeconds(30)));
        service = new RabbitAdminServiceImpl(amqpAdmin, rabbit, management, properties);
    }

    private static ManagementQueue queue(String name, Long messages, Long consumers) {
        return new ManagementQueue(name, "classic", true, false, Map.of(), messages, messages, 0L, consumers);
    }

    private static ManagementExchange exchange(String name, String type) {
        return new ManagementExchange(name, type, true, false, false, Map.of());
    }

    private static AmqpIOException channelClosed(int code, String text) {
        return new AmqpIOException(new IOException(new ShutdownSignalException(false, false,
                new AMQImpl.Channel.Close(code, text, 50, 10), null)));
    }

    // ------------------------------------------------------------ colas

    @Test
    void mapsManagementQueuesAndMarksProtectedOnes() {
        when(management.listQueues()).thenReturn(List.of(
                new ManagementQueue("q.cmd.email", "classic", true, false, null, null, null, null, null),
                queue("a.demo", 3L, 1L)));
        List<QueueResponse> queues = service.listQueues();
        assertThat(queues).extracting(QueueResponse::name).containsExactly("a.demo", "q.cmd.email");
        assertThat(queues.get(1).type()).isEqualTo("CLASSIC");
        assertThat(queues.get(1).messages()).isZero();
        assertThat(queues.get(1).arguments()).isEmpty();
        assertThat(queues.get(1).protectedResource()).isTrue();
        assertThat(queues.get(0).consumers()).isEqualTo(1);
    }

    @Test
    void declaresQuorumQueueWithEveryRequestedArgument() {
        when(management.findQueue("q.demo")).thenReturn(Optional.empty());
        when(management.findExchange("cmd.dead.dlx")).thenReturn(Optional.of(exchange("cmd.dead.dlx", "direct")));
        QueueResponse created = service.createQueue(new CreateQueueRequest("q.demo", QueueType.QUORUM, true, false,
                "cmd.dead.dlx", "demo.failed", 604_800_000, 10_000));
        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(captor.capture());
        Queue queue = captor.getValue();
        assertThat(queue.getName()).isEqualTo("q.demo");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-exchange", "cmd.dead.dlx")
                .containsEntry("x-dead-letter-routing-key", "demo.failed")
                .containsEntry("x-message-ttl", 604_800_000)
                .containsEntry("x-max-length", 10_000L);
        assertThat(created.type()).isEqualTo("QUORUM");
        assertThat(created.protectedResource()).isFalse();
    }

    @Test
    void declaresClassicQueueByDefault() {
        when(management.findQueue("q.demo")).thenReturn(Optional.empty());
        service.createQueue(new CreateQueueRequest("q.demo", null, null, null, null, null, null, null));
        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(captor.capture());
        assertThat(captor.getValue().getArguments()).containsOnly(Map.entry("x-queue-type", "classic"));
        verify(management, never()).findExchange(anyString());
    }

    @Test
    void rejectsExistingReservedOrInvalidQueues() {
        when(management.findQueue("q.demo")).thenReturn(Optional.of(queue("q.demo", 0L, 0L)));
        assertThatThrownBy(() -> service.createQueue(simpleQueue("q.demo")))
                .isInstanceOf(ResourceConflictException.class).hasMessage("La cola 'q.demo' ya existe");
        assertThatThrownBy(() -> service.createQueue(simpleQueue("q.audit.dead-letters")))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("nombre reservado");
        when(management.findQueue("q.other")).thenReturn(Optional.empty());
        when(management.findExchange("dlx.none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createQueue(new CreateQueueRequest("q.other", null, null, null,
                "dlx.none", null, null, null)))
                .isInstanceOf(InvalidAdminRequestException.class).hasMessageContaining("'dlx.none' no existe");
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void translatesBrokerPreconditionFailedIntoConflict() {
        when(management.findQueue("q.demo")).thenReturn(Optional.empty());
        when(amqpAdmin.declareQueue(any())).thenThrow(channelClosed(406,
                "PRECONDITION_FAILED - inequivalent arg 'x-queue-type' for queue 'q.demo'"));
        assertThatThrownBy(() -> service.createQueue(simpleQueue("q.demo")))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("PRECONDITION_FAILED");
    }

    @Test
    void translatesConnectionFailuresIntoUnavailable() {
        when(management.findQueue("q.demo")).thenReturn(Optional.empty());
        when(amqpAdmin.declareQueue(any())).thenThrow(new AmqpConnectException(new java.net.ConnectException("refused")));
        assertThatThrownBy(() -> service.createQueue(simpleQueue("q.demo")))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("RabbitMQ no está disponible");
    }

    @Test
    void neverDeletesProtectedQueues() {
        assertThatThrownBy(() -> service.deleteQueue("q.cmd.email", false, false))
                .isInstanceOf(ProtectedResourceException.class)
                .hasMessageContaining("topología base");
        verifyNoInteractions(amqpAdmin, management);
    }

    @Test
    void deletesExistingQueuesAndChecksPreconditions() {
        when(management.findQueue("q.demo")).thenReturn(Optional.of(queue("q.demo", 0L, 0L)));
        service.deleteQueue("q.demo", true, true);
        verify(amqpAdmin).deleteQueue("q.demo", true, true);

        when(management.findQueue("q.busy")).thenReturn(Optional.of(queue("q.busy", 5L, 1L)));
        assertThatThrownBy(() -> service.deleteQueue("q.busy", true, false))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("consumidor");
        assertThatThrownBy(() -> service.deleteQueue("q.busy", false, true))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("5 mensaje");

        when(management.findQueue("q.none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteQueue("q.none", false, false))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("La cola 'q.none' no existe");
        verify(amqpAdmin, never()).deleteQueue(eq("q.busy"), anyBoolean(), anyBoolean());
    }

    @Test
    void purgesExistingQueues() {
        when(management.findQueue(EMAIL_DLQ)).thenReturn(Optional.of(queue(EMAIL_DLQ, 4L, 0L)));
        when(amqpAdmin.purgeQueue(EMAIL_DLQ)).thenReturn(4);
        assertThat(service.purgeQueue(EMAIL_DLQ).purged()).isEqualTo(4);
        when(management.findQueue("q.none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.purgeQueue("q.none")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ exchanges

    @Test
    void createsAndDeletesExchanges() throws Exception {
        when(management.findExchange("ex.demo")).thenReturn(Optional.empty());
        service.createExchange(new CreateExchangeRequest("ex.demo", ExchangeType.TOPIC, null, true));
        ArgumentCaptor<Exchange> captor = ArgumentCaptor.forClass(Exchange.class);
        verify(amqpAdmin).declareExchange(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("topic");
        assertThat(captor.getValue().isDurable()).isTrue();
        assertThat(captor.getValue().isAutoDelete()).isTrue();

        when(management.findExchange("ex.demo")).thenReturn(Optional.of(exchange("ex.demo", "topic")));
        assertThatThrownBy(() -> service.createExchange(new CreateExchangeRequest("ex.demo", ExchangeType.TOPIC, null, null)))
                .isInstanceOf(ResourceConflictException.class);

        service.deleteExchange("ex.demo", false);
        verify(amqpAdmin).deleteExchange("ex.demo");

        when(management.bindingsFromExchange("ex.demo")).thenReturn(List.of());
        service.deleteExchange("ex.demo", true);
        verify(channel).exchangeDelete("ex.demo", true);
    }

    @Test
    void protectsBaseExchangesAndChecksIfUnused() {
        assertThatThrownBy(() -> service.deleteExchange("cmd.direct", false))
                .isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.createExchange(new CreateExchangeRequest("cmd.topic", ExchangeType.TOPIC, null, null)))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("reservado");
        when(management.findExchange("ex.used")).thenReturn(Optional.of(exchange("ex.used", "direct")));
        when(management.bindingsFromExchange("ex.used")).thenReturn(List.of(
                new ManagementBinding("ex.used", "q.demo", "queue", "a", Map.of())));
        assertThatThrownBy(() -> service.deleteExchange("ex.used", true))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("1 binding");
        when(management.findExchange("ex.none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteExchange("ex.none", false))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(amqpAdmin);
    }

    // ------------------------------------------------------------ bindings

    @Test
    void acceptsWildcardsOnlyOnTopicExchanges() {
        when(management.findExchange("cmd.direct")).thenReturn(Optional.of(exchange("cmd.direct", "direct")));
        when(management.findExchange("ex.topic")).thenReturn(Optional.of(exchange("ex.topic", "topic")));
        when(management.findQueue("q.demo")).thenReturn(Optional.of(queue("q.demo", 0L, 0L)));
        assertThatThrownBy(() -> service.createBinding(new BindingRequest("cmd.direct", "q.demo", "email.*")))
                .isInstanceOf(InvalidAdminRequestException.class)
                .hasMessageContaining("solo se permiten en exchanges topic")
                .hasMessageContaining("DIRECT");
        when(management.bindingsBetween("ex.topic", "q.demo")).thenReturn(List.of());
        service.createBinding(new BindingRequest("ex.topic", "q.demo", "demo.#"));
        ArgumentCaptor<Binding> captor = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(captor.capture());
        assertThat(captor.getValue().getExchange()).isEqualTo("ex.topic");
        assertThat(captor.getValue().getDestination()).isEqualTo("q.demo");
        assertThat(captor.getValue().getRoutingKey()).isEqualTo("demo.#");
    }

    @Test
    void rejectsDuplicatedOrDanglingBindings() {
        when(management.findExchange("ex.demo")).thenReturn(Optional.of(exchange("ex.demo", "direct")));
        when(management.findQueue("q.demo")).thenReturn(Optional.of(queue("q.demo", 0L, 0L)));
        when(management.bindingsBetween("ex.demo", "q.demo")).thenReturn(List.of(
                new ManagementBinding("ex.demo", "q.demo", "queue", "a", Map.of())));
        assertThatThrownBy(() -> service.createBinding(new BindingRequest("ex.demo", "q.demo", "a")))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("ya existe");
        assertThatThrownBy(() -> service.deleteBinding("ex.demo", "q.demo", "b"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("no existe");
        when(management.findQueue("q.none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createBinding(new BindingRequest("ex.demo", "q.none", "a")))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("La cola 'q.none' no existe");
        verifyNoInteractions(amqpAdmin);

        service.deleteBinding("ex.demo", "q.demo", "a");
        ArgumentCaptor<Binding> captor = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).removeBinding(captor.capture());
        assertThat(captor.getValue().getRoutingKey()).isEqualTo("a");
    }

    @Test
    void listsOnlyExplicitQueueBindings() {
        when(management.listBindings()).thenReturn(List.of(
                new ManagementBinding("", "q.demo", "queue", "q.demo", Map.of()),
                new ManagementBinding("ex.a", "ex.b", "exchange", "x", Map.of()),
                new ManagementBinding("cmd.direct", "q.cmd.email", "queue", "email.send", Map.of())));
        assertThat(service.listBindings(null, null)).singleElement()
                .satisfies(binding -> assertThat(binding.routingKey()).isEqualTo("email.send"));
        when(management.findExchange("ex.none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listBindings("ex.none", null)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ DLQ

    @Test
    void computesDeadLetterAlertsAgainstTheThreshold() {
        when(management.findQueue(EMAIL_DLQ)).thenReturn(Optional.of(queue(EMAIL_DLQ, 2L, 0L)));
        when(management.findQueue("q.cmd.kitchen.dlq")).thenReturn(Optional.of(queue("q.cmd.kitchen.dlq", 1L, 0L)));
        when(management.findQueue("q.audit.dead-letters.dlq")).thenReturn(Optional.empty());
        List<DeadLetterQueueStatus> statuses = service.deadLetterQueues();
        assertThat(statuses).containsExactly(
                new DeadLetterQueueStatus(EMAIL_DLQ, true, 2, 2, true),
                new DeadLetterQueueStatus("q.cmd.kitchen.dlq", true, 1, 2, false),
                new DeadLetterQueueStatus("q.audit.dead-letters.dlq", false, 0, 2, false));
    }

    @Test
    void replaysOnlyConfiguredDeadLetterQueues() {
        assertThatThrownBy(() -> service.replayDeadLetters("q.demo", 5))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("no es una DLQ configurada");
        when(management.findQueue("q.cmd.kitchen.dlq")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replayDeadLetters("q.cmd.kitchen.dlq", 5))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(rabbit);
    }

    @Test
    void replaysToTheOriginalExchangeAndAcksOnlyAfterTheConfirm() throws Exception {
        when(management.findQueue(EMAIL_DLQ)).thenReturn(Optional.of(queue(EMAIL_DLQ, 2L, 0L)));
        when(management.findExchange("cmd.direct")).thenReturn(Optional.of(exchange("cmd.direct", "direct")));
        GetResponse dead = new GetResponse(new Envelope(1, false, "cmd.dead.dlx", "email.send"),
                deadLetterProps("m-1", "cmd.direct", "q.cmd.email", "email.send"), body(), 1);
        GetResponse orphan = new GetResponse(new Envelope(2, false, "", EMAIL_DLQ),
                new AMQP.BasicProperties.Builder().messageId("m-2").build(), body(), 0);
        when(channel.basicGet(EMAIL_DLQ, false)).thenReturn(dead, orphan, null);
        when(channel.waitForConfirms(anyLong())).thenReturn(true);

        ReplayResponse result = service.replayDeadLetters(EMAIL_DLQ, 10);

        assertThat(result).isEqualTo(new ReplayResponse(1, 1));
        ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        InOrder order = inOrder(channel);
        order.verify(channel).confirmSelect();
        order.verify(channel).basicPublish(eq("cmd.direct"), eq("email.send"), eq(true), props.capture(), eq(body()));
        order.verify(channel).waitForConfirms(5000);
        order.verify(channel).basicAck(1, false);
        order.verify(channel).basicNack(2, false, true);
        verify(channel, never()).basicAck(2, false);
        assertThat(props.getValue().getMessageId()).isEqualTo("m-1");
        assertThat(props.getValue().getHeaders())
                .doesNotContainKeys("x-death", "x-first-death-queue", "x-first-death-exchange")
                .containsEntry("x-trace-id", "trace-1")
                .containsEntry(RabbitAdminServiceImpl.REPLAY_HEADER, EMAIL_DLQ);
    }

    @Test
    void keepsUnroutableOrUnconfirmedMessagesInTheDeadLetterQueue() throws Exception {
        when(management.findQueue(EMAIL_DLQ)).thenReturn(Optional.of(queue(EMAIL_DLQ, 2L, 0L)));
        when(management.findExchange("cmd.direct")).thenReturn(Optional.of(exchange("cmd.direct", "direct")));
        when(management.findExchange("cmd.gone")).thenReturn(Optional.empty());
        AtomicReference<ReturnCallback> callback = new AtomicReference<>();
        when(channel.addReturnListener(any(ReturnCallback.class))).thenAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            return mock(ReturnListener.class);
        });
        doAnswer(invocation -> {
            callback.get().handle(mock(Return.class));
            return null;
        }).when(channel).basicPublish(eq("cmd.direct"), eq("email.unbound"), eq(true), any(), any());
        when(channel.waitForConfirms(anyLong())).thenReturn(true, false);
        when(channel.basicGet(EMAIL_DLQ, false)).thenReturn(
                new GetResponse(new Envelope(1, false, "", ""), deadLetterProps("m-1", "cmd.direct", "q.cmd.email", "email.unbound"), body(), 3),
                new GetResponse(new Envelope(2, false, "", ""), deadLetterProps("m-2", "cmd.direct", "q.cmd.email", "email.send"), body(), 2),
                new GetResponse(new Envelope(3, false, "", ""), deadLetterProps("m-3", "cmd.gone", "q.cmd.email", "email.send"), body(), 1));

        ReplayResponse result = service.replayDeadLetters(EMAIL_DLQ, 3);

        assertThat(result).isEqualTo(new ReplayResponse(0, 3));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(1, false, true);
        verify(channel).basicNack(2, false, true);
        verify(channel).basicNack(3, false, true);
        verify(channel, never()).basicPublish(eq("cmd.gone"), anyString(), anyBoolean(), any(), any());
    }

    @Test
    void resolvesTheOriginalDestination() {
        assertThat(service.originalDestination(deadLetterProps("m", "cmd.topic", "q.cmd.email", "email.send.high")))
                .contains(new RabbitAdminServiceImpl.Destination("cmd.topic", "email.send.high"));
        // Murió en la cola de auditoría: se devuelve directo a esa cola, sin pasar otra vez por el DLX.
        assertThat(service.originalDestination(deadLetterProps("m", "cmd.dead.dlx", "q.audit.dead-letters", "email.send")))
                .contains(new RabbitAdminServiceImpl.Destination("", "q.audit.dead-letters"));
        AMQP.BasicProperties onlyFirstDeath = new AMQP.BasicProperties.Builder()
                .headers(Map.of("x-first-death-queue", "q.cmd.kitchen")).build();
        assertThat(service.originalDestination(onlyFirstDeath))
                .contains(new RabbitAdminServiceImpl.Destination("", "q.cmd.kitchen"));
        assertThat(service.originalDestination(new AMQP.BasicProperties.Builder().build())).isEmpty();
    }

    @Test
    void detectsWildcardSegments() {
        assertThat(RabbitAdminServiceImpl.hasWildcards("email.#")).isTrue();
        assertThat(RabbitAdminServiceImpl.hasWildcards("*.ticket")).isTrue();
        assertThat(RabbitAdminServiceImpl.hasWildcards("email.send")).isFalse();
        assertThat(RabbitAdminServiceImpl.hasWildcards("")).isFalse();
    }

    private static CreateQueueRequest simpleQueue(String name) {
        return new CreateQueueRequest(name, null, null, null, null, null, null, null);
    }

    private static byte[] body() {
        return "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
    }

    private static AMQP.BasicProperties deadLetterProps(String messageId, String exchange, String queue, String routingKey) {
        Map<String, Object> death = Map.of("exchange", exchange, "queue", queue, "reason", "rejected",
                "count", 1L, "routing-keys", List.of(routingKey));
        return new AMQP.BasicProperties.Builder().messageId(messageId).contentType("application/json")
                .headers(Map.of("x-death", List.of(death), "x-first-death-queue", queue,
                        "x-first-death-exchange", exchange, "x-trace-id", "trace-1"))
                .build();
    }
}
