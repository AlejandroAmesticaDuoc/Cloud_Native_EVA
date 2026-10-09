package cl.duoc.pedidos360.report.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Topología del consumidor de boletas sin broker: argumentos y tipos idénticos a los de orders. */
class RabbitTopologyConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitTopologyConfig.class)
            .withPropertyValues("messaging.exchanges.direct=cmd.direct", "messaging.exchanges.topic=cmd.topic",
                    "messaging.exchanges.dead-letter=cmd.dead.dlx",
                    "messaging.routes.invoice.queue=q.cmd.invoice", "messaging.routes.invoice.dlq=q.cmd.invoice.dlq",
                    "messaging.routes.invoice.routing-key=invoice.gen", "messaging.routes.invoice.topic-pattern=invoice.#",
                    "messaging.queues.max-length=10000", "messaging.queues.dead-letter-ttl=7d",
                    "messaging.queues.dead-letter-max-length=10000", "messaging.consumer.prefetch=1",
                    "messaging.consumer.max-payload-bytes=32768", "messaging.consumer.retry.max-attempts=3",
                    "messaging.consumer.retry.initial-interval=1s", "messaging.consumer.retry.multiplier=2",
                    "messaging.consumer.retry.max-interval=4s");

    @Test
    void declaresTheInvoiceRouteWithTheContractArguments() {
        runner.withPropertyValues("report.commands.enabled=true").run(context -> {
            assertEquals(Set.of("cmd.direct", "cmd.topic", "cmd.dead.dlx"), context.getBeansOfType(Exchange.class)
                    .values().stream().filter(Exchange::isDurable).map(Exchange::getName).collect(Collectors.toSet()));
            var queues = context.getBeansOfType(Queue.class).values().stream()
                    .collect(Collectors.toMap(Queue::getName, Queue::getArguments));
            assertEquals(Map.of("q.cmd.invoice", Map.of("x-dead-letter-exchange", "cmd.dead.dlx",
                    "x-dead-letter-routing-key", "invoice.gen", "x-max-length", 10000L, "x-overflow", "reject-publish"),
                    "q.cmd.invoice.dlq", Map.of("x-message-ttl", 604800000, "x-max-length", 10000L)), queues);
            assertInstanceOf(Integer.class, queues.get("q.cmd.invoice.dlq").get("x-message-ttl"));
            assertEquals(Set.of("cmd.direct--invoice.gen-->q.cmd.invoice", "cmd.topic--invoice.#-->q.cmd.invoice",
                    "cmd.dead.dlx--invoice.gen-->q.cmd.invoice.dlq"), context.getBeansOfType(Binding.class).values()
                    .stream().map(binding -> binding.getExchange() + "--" + binding.getRoutingKey() + "-->"
                            + binding.getDestination()).collect(Collectors.toSet()));
        });
    }

    @Test
    void declaresNothingWhenCommandsAreDisabled() {
        runner.withPropertyValues("report.commands.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(Queue.class).isEmpty()));
    }
}
