package cl.duoc.pedidos360.orders.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifica la topología del productor sin broker: nombres, tipos y argumentos exactos del contrato. */
class RabbitTopologyConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitTopologyConfig.class)
            .withPropertyValues("orders.notifications.enabled=true",
                    "messaging.exchanges.direct=cmd.direct", "messaging.exchanges.topic=cmd.topic",
                    "messaging.exchanges.dead-letter=cmd.dead.dlx",
                    "messaging.routes.email.queue=q.cmd.email", "messaging.routes.email.dlq=q.cmd.email.dlq",
                    "messaging.routes.email.routing-key=email.send", "messaging.routes.email.topic-pattern=email.#",
                    "messaging.routes.email.priority-routing-key=email.send.high",
                    "messaging.routes.kitchen.queue=q.cmd.kitchen", "messaging.routes.kitchen.dlq=q.cmd.kitchen.dlq",
                    "messaging.routes.kitchen.routing-key=kitchen.ticket", "messaging.routes.kitchen.topic-pattern=kitchen.#",
                    "messaging.routes.invoice.queue=q.cmd.invoice", "messaging.routes.invoice.dlq=q.cmd.invoice.dlq",
                    "messaging.routes.invoice.routing-key=invoice.gen", "messaging.routes.invoice.topic-pattern=invoice.#",
                    "messaging.queues.max-length=10000", "messaging.queues.dead-letter-ttl=7d",
                    "messaging.queues.dead-letter-max-length=10000");

    @Test
    void declaresThreeDurableExchangesSixQueuesAndNineBindings() {
        runner.run(context -> {
            var exchanges = context.getBeansOfType(Exchange.class).values().stream()
                    .collect(Collectors.toMap(Exchange::getName, Exchange::getType));
            assertEquals(Map.of("cmd.direct", ExchangeTypes.DIRECT, "cmd.topic", ExchangeTypes.TOPIC,
                    "cmd.dead.dlx", ExchangeTypes.DIRECT), exchanges);
            assertTrue(context.getBeansOfType(Exchange.class).values().stream()
                    .allMatch(exchange -> exchange.isDurable() && !exchange.isAutoDelete() && exchange.getArguments().isEmpty()));
            assertEquals(6, context.getBeansOfType(Queue.class).size());
            var bindings = context.getBeansOfType(Binding.class).values().stream()
                    .map(binding -> binding.getExchange() + "--" + binding.getRoutingKey() + "-->" + binding.getDestination())
                    .collect(Collectors.toSet());
            assertEquals(Set.of(
                    "cmd.direct--email.send-->q.cmd.email", "cmd.direct--kitchen.ticket-->q.cmd.kitchen",
                    "cmd.direct--invoice.gen-->q.cmd.invoice", "cmd.topic--email.#-->q.cmd.email",
                    "cmd.topic--kitchen.#-->q.cmd.kitchen", "cmd.topic--invoice.#-->q.cmd.invoice",
                    "cmd.dead.dlx--email.send-->q.cmd.email.dlq", "cmd.dead.dlx--kitchen.ticket-->q.cmd.kitchen.dlq",
                    "cmd.dead.dlx--invoice.gen-->q.cmd.invoice.dlq"), bindings);
        });
    }

    @Test
    void usesTheExactQueueArgumentsAndTypesOfTheContract() {
        runner.run(context -> {
            var queues = context.getBeansOfType(Queue.class).values().stream()
                    .collect(Collectors.toMap(Queue::getName, Queue::getArguments));
            for (String route : new String[] {"email.send", "kitchen.ticket", "invoice.gen"}) {
                String name = "q.cmd." + route.substring(0, route.indexOf('.'));
                assertEquals(Map.of("x-dead-letter-exchange", "cmd.dead.dlx", "x-dead-letter-routing-key", route,
                        "x-max-length", 10000L, "x-overflow", "reject-publish"), queues.get(name));
                assertEquals(Map.of("x-message-ttl", 604800000, "x-max-length", 10000L), queues.get(name + ".dlq"));
                assertInstanceOf(Long.class, queues.get(name).get("x-max-length"));
                assertInstanceOf(Integer.class, queues.get(name + ".dlq").get("x-message-ttl"));
            }
            assertTrue(context.getBeansOfType(Queue.class).values().stream()
                    .allMatch(queue -> queue.isDurable() && !queue.isAutoDelete() && !queue.isExclusive()));
        });
    }

    @Test
    void declaresNothingWhenPublishingIsDisabled() {
        new ApplicationContextRunner().withUserConfiguration(RabbitTopologyConfig.class)
                .withPropertyValues("orders.notifications.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(Queue.class).isEmpty()));
    }
}
