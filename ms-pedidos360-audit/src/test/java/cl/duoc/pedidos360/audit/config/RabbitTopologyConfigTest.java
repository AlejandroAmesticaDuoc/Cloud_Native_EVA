package cl.duoc.pedidos360.audit.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Topología de auditoría sin broker: cola de copia, su DLQ y los 4 bindings sobre cmd.dead.dlx. */
class RabbitTopologyConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitTopologyConfig.class)
            .withPropertyValues("messaging.exchanges.dead-letter=cmd.dead.dlx",
                    "messaging.dead-letters.queue=q.audit.dead-letters",
                    "messaging.dead-letters.dlq=q.audit.dead-letters.dlq",
                    "messaging.dead-letters.routing-key=audit.dead-letters",
                    "messaging.dead-letters.source-routing-keys=email.send,kitchen.ticket,invoice.gen",
                    "messaging.queues.max-length=10000", "messaging.queues.dead-letter-ttl=7d",
                    "messaging.queues.dead-letter-max-length=10000", "messaging.consumer.prefetch=1",
                    "messaging.consumer.max-payload-bytes=65536", "messaging.consumer.retry.max-attempts=3",
                    "messaging.consumer.retry.initial-interval=1s", "messaging.consumer.retry.multiplier=2",
                    "messaging.consumer.retry.max-interval=4s");

    @Test
    void declaresTheAuditCopyOfEveryCommandDeadLetter() {
        runner.withPropertyValues("audit.dead-letters.enabled=true").run(context -> {
            assertEquals(Set.of("cmd.dead.dlx"), context.getBeansOfType(Exchange.class).values().stream()
                    .filter(Exchange::isDurable).map(Exchange::getName).collect(Collectors.toSet()));
            var queues = context.getBeansOfType(Queue.class).values().stream()
                    .collect(Collectors.toMap(Queue::getName, Queue::getArguments));
            assertEquals(Map.of("q.audit.dead-letters", Map.of("x-dead-letter-exchange", "cmd.dead.dlx",
                    "x-dead-letter-routing-key", "audit.dead-letters", "x-max-length", 10000L),
                    "q.audit.dead-letters.dlq", Map.of("x-message-ttl", 604800000, "x-max-length", 10000L)), queues);
            var bindings = Stream.concat(context.getBeansOfType(Binding.class).values().stream(),
                    context.getBeansOfType(Declarables.class).values().stream()
                            .flatMap(declarables -> declarables.getDeclarablesByType(Binding.class).stream()))
                    .map(binding -> binding.getExchange() + "--" + binding.getRoutingKey() + "-->" + binding.getDestination())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("cmd.dead.dlx--email.send-->q.audit.dead-letters",
                    "cmd.dead.dlx--kitchen.ticket-->q.audit.dead-letters",
                    "cmd.dead.dlx--invoice.gen-->q.audit.dead-letters",
                    "cmd.dead.dlx--audit.dead-letters-->q.audit.dead-letters.dlq"), bindings);
        });
    }

    @Test
    void declaresNothingWhenAuditOfDeadLettersIsDisabled() {
        runner.withPropertyValues("audit.dead-letters.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(Queue.class).isEmpty()));
    }
}
