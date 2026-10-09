package cl.duoc.pedidos360.mqadmin.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import cl.duoc.pedidos360.mqadmin.client.ManagementBinding;
import cl.duoc.pedidos360.mqadmin.client.ManagementExchange;
import cl.duoc.pedidos360.mqadmin.client.ManagementQueue;
import cl.duoc.pedidos360.mqadmin.client.RabbitManagementClient;
import cl.duoc.pedidos360.mqadmin.config.MqAdminProperties;
import cl.duoc.pedidos360.mqadmin.dto.BindingRequest;
import cl.duoc.pedidos360.mqadmin.dto.BindingResponse;
import cl.duoc.pedidos360.mqadmin.dto.CreateExchangeRequest;
import cl.duoc.pedidos360.mqadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.mqadmin.dto.DeadLetterQueueStatus;
import cl.duoc.pedidos360.mqadmin.dto.ExchangeResponse;
import cl.duoc.pedidos360.mqadmin.dto.ExchangeType;
import cl.duoc.pedidos360.mqadmin.dto.PurgeResponse;
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
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownSignalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.stereotype.Service;

/**
 * Implementación de la administración de RabbitMQ.
 * <ul>
 *   <li>Mutaciones (declarar, eliminar, enlazar, purgar) por AMQP con {@link AmqpAdmin} (RabbitAdmin).</li>
 *   <li>Lecturas y verificación de existencia o tipo con {@link RabbitManagementClient}.</li>
 *   <li>Errores del broker traducidos a excepciones de dominio: 404 NOT_FOUND -> no existe;
 *       406 PRECONDITION_FAILED, 405 y 403 -> conflicto; sin conexión -> no disponible.</li>
 * </ul>
 */
@Service
public class RabbitAdminServiceImpl implements RabbitAdminService {
    static final String REPLAY_HEADER = "x-replayed-from";
    private static final Logger LOG = LoggerFactory.getLogger(RabbitAdminServiceImpl.class);
    private static final String DEFAULT_EXCHANGE = "";

    private final AmqpAdmin amqpAdmin;
    private final RabbitOperations rabbit;
    private final RabbitManagementClient management;
    private final MqAdminProperties properties;

    public RabbitAdminServiceImpl(AmqpAdmin amqpAdmin, RabbitOperations rabbit,
            RabbitManagementClient management, MqAdminProperties properties) {
        this.amqpAdmin = amqpAdmin;
        this.rabbit = rabbit;
        this.management = management;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- colas

    @Override
    public List<QueueResponse> listQueues() {
        return management.listQueues().stream()
                .map(this::toResponse)
                .sorted(Comparator.comparing(QueueResponse::name))
                .toList();
    }

    @Override
    public QueueResponse getQueue(String name) {
        return toResponse(requireQueue(name));
    }

    @Override
    public QueueResponse createQueue(CreateQueueRequest request) {
        String name = request.name();
        rejectReservedName(name);
        if (management.findQueue(name).isPresent()) {
            throw new ResourceConflictException("La cola '%s' ya existe".formatted(name));
        }
        if (request.deadLetterExchange() != null && management.findExchange(request.deadLetterExchange()).isEmpty()) {
            throw new InvalidAdminRequestException("El deadLetterExchange '%s' no existe; créalo antes de usarlo en la cola"
                    .formatted(request.deadLetterExchange()));
        }
        Queue queue = buildQueue(request);
        broker("crear la cola '" + name + "'", () -> amqpAdmin.declareQueue(queue));
        LOG.info("Cola creada: cola={} tipo={} durable={} autoDelete={} argumentos={}",
                name, request.type(), request.durable(), request.autoDelete(), queue.getArguments());
        return new QueueResponse(name, request.type().name(), queue.isDurable(), queue.isAutoDelete(),
                queue.getArguments(), 0, 0, 0, 0, false);
    }

    @Override
    public void deleteQueue(String name, boolean ifUnused, boolean ifEmpty) {
        if (properties.isProtected(name)) {
            LOG.warn("Eliminación rechazada de cola protegida: cola={}", name);
            throw new ProtectedResourceException("La cola", name);
        }
        ManagementQueue current = requireQueue(name);
        // Revisión previa con las estadísticas de management; el broker vuelve a comprobarlo (406).
        if (ifUnused && count(current.consumers()) > 0) {
            throw new ResourceConflictException("La cola '%s' tiene %d consumidor(es) y se pidió ifUnused=true"
                    .formatted(name, count(current.consumers())));
        }
        if (ifEmpty && count(current.messages()) > 0) {
            throw new ResourceConflictException("La cola '%s' tiene %d mensaje(s) y se pidió ifEmpty=true"
                    .formatted(name, count(current.messages())));
        }
        broker("eliminar la cola '" + name + "'", () -> {
            amqpAdmin.deleteQueue(name, ifUnused, ifEmpty);
            return null;
        });
        LOG.info("Cola eliminada: cola={} ifUnused={} ifEmpty={}", name, ifUnused, ifEmpty);
    }

    @Override
    public PurgeResponse purgeQueue(String name) {
        requireQueue(name);
        int purged = broker("vaciar la cola '" + name + "'", () -> amqpAdmin.purgeQueue(name));
        LOG.warn("Cola vaciada: cola={} mensajesEliminados={}", name, purged);
        return new PurgeResponse(name, purged);
    }

    // ---------------------------------------------------------------- exchanges

    @Override
    public List<ExchangeResponse> listExchanges() {
        return management.listExchanges().stream()
                .map(exchange -> new ExchangeResponse(exchange.name(), upper(exchange.type()), exchange.durable(),
                        exchange.autoDelete(), exchange.internal(), arguments(exchange.arguments()),
                        properties.isProtected(exchange.name())))
                .sorted(Comparator.comparing(ExchangeResponse::name))
                .toList();
    }

    @Override
    public ExchangeResponse createExchange(CreateExchangeRequest request) {
        String name = request.name();
        rejectReservedName(name);
        if (management.findExchange(name).isPresent()) {
            throw new ResourceConflictException("El exchange '%s' ya existe".formatted(name));
        }
        ExchangeBuilder builder = new ExchangeBuilder(name, amqpType(request.type())).durable(request.durable());
        if (request.autoDelete()) {
            builder.autoDelete();
        }
        Exchange exchange = builder.build();
        broker("crear el exchange '" + name + "'", () -> {
            amqpAdmin.declareExchange(exchange);
            return null;
        });
        LOG.info("Exchange creado: exchange={} tipo={} durable={} autoDelete={}",
                name, request.type(), request.durable(), request.autoDelete());
        return new ExchangeResponse(name, request.type().name(), exchange.isDurable(), exchange.isAutoDelete(),
                false, Map.of(), false);
    }

    @Override
    public void deleteExchange(String name, boolean ifUnused) {
        if (properties.isProtected(name)) {
            LOG.warn("Eliminación rechazada de exchange protegido: exchange={}", name);
            throw new ProtectedResourceException("El exchange", name);
        }
        requireExchange(name);
        if (ifUnused) {
            int bindings = management.bindingsFromExchange(name).size();
            if (bindings > 0) {
                throw new ResourceConflictException("El exchange '%s' tiene %d binding(s) y se pidió ifUnused=true"
                        .formatted(name, bindings));
            }
            // AmqpAdmin no expone if-unused para exchanges: se usa el canal directamente.
            broker("eliminar el exchange '" + name + "'", () -> rabbit.execute(channel -> channel.exchangeDelete(name, true)));
        } else {
            broker("eliminar el exchange '" + name + "'", () -> amqpAdmin.deleteExchange(name));
        }
        LOG.info("Exchange eliminado: exchange={} ifUnused={}", name, ifUnused);
    }

    // ---------------------------------------------------------------- bindings

    @Override
    public List<BindingResponse> listBindings(String exchange, String queue) {
        List<ManagementBinding> bindings;
        if (exchange != null && queue != null) {
            requireExchange(exchange);
            requireQueue(queue);
            bindings = management.bindingsBetween(exchange, queue);
        } else if (exchange != null) {
            requireExchange(exchange);
            bindings = management.bindingsFromExchange(exchange);
        } else if (queue != null) {
            requireQueue(queue);
            bindings = management.bindingsToQueue(queue);
        } else {
            bindings = management.listBindings();
        }
        // Se omiten los bindings implícitos del exchange por defecto y los enlaces exchange -> exchange.
        return bindings.stream()
                .filter(binding -> binding.targetsQueue() && !DEFAULT_EXCHANGE.equals(binding.source()))
                .map(binding -> new BindingResponse(binding.source(), binding.destination(), binding.routingKey(),
                        arguments(binding.arguments())))
                .sorted(Comparator.comparing(BindingResponse::exchange).thenComparing(BindingResponse::queue)
                        .thenComparing(BindingResponse::routingKey))
                .toList();
    }

    @Override
    public BindingResponse createBinding(BindingRequest request) {
        ManagementExchange exchange = requireExchange(request.exchange());
        requireQueue(request.queue());
        String routingKey = request.routingKey();
        if (hasWildcards(routingKey) && !ExchangeTypes.TOPIC.equals(exchange.type())) {
            throw new InvalidAdminRequestException(("Los comodines '*' y '#' solo se permiten en exchanges topic; "
                    + "'%s' es de tipo %s").formatted(exchange.name(), upper(exchange.type())));
        }
        if (findBinding(request.exchange(), request.queue(), routingKey).isPresent()) {
            throw new ResourceConflictException("El binding %s --%s--> %s ya existe"
                    .formatted(request.exchange(), routingKey, request.queue()));
        }
        Binding binding = new Binding(request.queue(), Binding.DestinationType.QUEUE, request.exchange(), routingKey, null);
        broker("crear el binding " + request.exchange() + " -> " + request.queue(), () -> {
            amqpAdmin.declareBinding(binding);
            return null;
        });
        LOG.info("Binding creado: exchange={} cola={} routingKey={}", request.exchange(), request.queue(), routingKey);
        return new BindingResponse(request.exchange(), request.queue(), routingKey, Map.of());
    }

    @Override
    public void deleteBinding(String exchange, String queue, String routingKey) {
        requireExchange(exchange);
        requireQueue(queue);
        ManagementBinding existing = findBinding(exchange, queue, routingKey)
                .orElseThrow(() -> new ResourceNotFoundException("El binding %s --%s--> %s no existe"
                        .formatted(exchange, routingKey, queue)));
        Map<String, Object> arguments = existing.arguments() == null || existing.arguments().isEmpty()
                ? null : existing.arguments();
        Binding binding = new Binding(queue, Binding.DestinationType.QUEUE, exchange, routingKey, arguments);
        broker("eliminar el binding " + exchange + " -> " + queue, () -> {
            amqpAdmin.removeBinding(binding);
            return null;
        });
        LOG.info("Binding eliminado: exchange={} cola={} routingKey={}", exchange, queue, routingKey);
    }

    // ---------------------------------------------------------------- DLQ

    @Override
    public List<DeadLetterQueueStatus> deadLetterQueues() {
        long threshold = properties.alerts().threshold();
        return properties.alerts().deadLetterQueues().stream()
                .map(name -> management.findQueue(name)
                        .map(queue -> {
                            long messages = count(queue.messages());
                            return new DeadLetterQueueStatus(name, true, messages, threshold, messages >= threshold);
                        })
                        .orElseGet(() -> new DeadLetterQueueStatus(name, false, 0, threshold, false)))
                .toList();
    }

    @Override
    public ReplayResponse replayDeadLetters(String queue, int maxMessages) {
        if (!properties.isDeadLetterQueue(queue)) {
            throw new ResourceNotFoundException("La cola '%s' no es una DLQ configurada en mq-admin (%s)"
                    .formatted(queue, String.join(", ", properties.alerts().deadLetterQueues())));
        }
        requireQueue(queue);
        ReplayResponse result = broker("reenviar mensajes desde la DLQ '" + queue + "'",
                () -> rabbit.execute(channel -> replay(channel, queue, maxMessages)));
        LOG.info("Replay de DLQ terminado: cola={} reenviados={} fallidos={}", queue, result.replayed(), result.failed());
        return result;
    }

    /**
     * Por cada mensaje: basicGet sin auto-ack, republicación con publisher confirm y recién entonces
     * basicAck. Si no se puede republicar, el mensaje queda sin confirmar y al final se devuelve a la
     * DLQ con basicNack(requeue=true); así no se vuelve a leer en la misma ejecución.
     */
    private ReplayResponse replay(Channel channel, String queue, int maxMessages) throws IOException, InterruptedException {
        channel.confirmSelect();
        AtomicBoolean returned = new AtomicBoolean();
        ReturnListener listener = channel.addReturnListener(unroutable -> returned.set(true));
        Map<String, Boolean> knownExchanges = new HashMap<>();
        List<Long> failed = new ArrayList<>();
        int replayed = 0;
        try {
            for (int index = 0; index < maxMessages; index++) {
                GetResponse response = channel.basicGet(queue, false);
                if (response == null) {
                    break;
                }
                long tag = response.getEnvelope().getDeliveryTag();
                String messageId = response.getProps().getMessageId();
                Optional<Destination> destination = originalDestination(response.getProps());
                String problem = destination.isEmpty() ? "sin x-death ni x-first-death-queue"
                        : !knownExchanges.computeIfAbsent(destination.get().exchange(), this::exchangeExists)
                                ? "el exchange original '" + destination.get().exchange() + "' ya no existe"
                                : republish(channel, destination.get(), response, queue, returned);
                if (problem == null) {
                    channel.basicAck(tag, false);
                    replayed++;
                    LOG.info("Mensaje reenviado desde DLQ: cola={} messageId={} exchange={} routingKey={}",
                            queue, messageId, destination.get().exchange(), destination.get().routingKey());
                } else {
                    failed.add(tag);
                    LOG.warn("No se pudo reenviar desde DLQ: cola={} messageId={} motivo={}", queue, messageId, problem);
                }
            }
        } finally {
            if (channel.isOpen()) {
                for (long tag : failed) {
                    channel.basicNack(tag, false, true);
                }
                channel.removeReturnListener(listener);
            }
        }
        return new ReplayResponse(replayed, failed.size());
    }

    /** Devuelve {@code null} si el broker confirmó la republicación o el motivo del fallo. */
    private String republish(Channel channel, Destination destination, GetResponse response, String queue,
            AtomicBoolean returned) throws IOException, InterruptedException {
        returned.set(false);
        channel.basicPublish(destination.exchange(), destination.routingKey(), true,
                withoutDeathHeaders(response.getProps(), queue), response.getBody());
        try {
            if (!channel.waitForConfirms(properties.replayConfirmTimeout().toMillis())) {
                return "el broker rechazó la publicación (nack)";
            }
        } catch (TimeoutException timeout) {
            return "sin confirmación del broker en " + properties.replayConfirmTimeout().toSeconds() + " s";
        }
        return returned.get() ? "ninguna cola recibió el mensaje con esa routing key" : null;
    }

    /**
     * Destino original: x-death[0].exchange + routing-keys[0]. Si el mensaje murió en una cola que
     * recibe dead letters (exchange original = DLX), se envía directo a esa cola por el exchange por
     * defecto para no duplicar copias en las demás DLQ. Sin x-death se usa x-first-death-queue.
     */
    Optional<Destination> originalDestination(AMQP.BasicProperties props) {
        Map<String, Object> headers = props.getHeaders();
        if (headers == null) {
            return Optional.empty();
        }
        if (headers.get("x-death") instanceof List<?> deaths && !deaths.isEmpty()
                && deaths.getFirst() instanceof Map<?, ?> death) {
            String exchange = text(death.get("exchange"));
            String deadQueue = text(death.get("queue"));
            String routingKey = death.get("routing-keys") instanceof List<?> keys && !keys.isEmpty()
                    ? text(keys.getFirst()) : null;
            if (exchange != null && properties.deadLetterExchange().equals(exchange) && deadQueue != null) {
                return Optional.of(new Destination(DEFAULT_EXCHANGE, deadQueue));
            }
            if (exchange != null && routingKey != null) {
                return Optional.of(new Destination(exchange, routingKey));
            }
        }
        String firstQueue = text(headers.get("x-first-death-queue"));
        return firstQueue == null ? Optional.empty() : Optional.of(new Destination(DEFAULT_EXCHANGE, firstQueue));
    }

    private static AMQP.BasicProperties withoutDeathHeaders(AMQP.BasicProperties props, String queue) {
        Map<String, Object> headers = new HashMap<>(props.getHeaders() == null ? Map.of() : props.getHeaders());
        headers.keySet().removeIf(key -> key.equals("x-death") || key.startsWith("x-first-death-")
                || key.startsWith("x-last-death-"));
        headers.put(REPLAY_HEADER, queue);
        return props.builder().headers(headers).build();
    }

    private boolean exchangeExists(String exchange) {
        return DEFAULT_EXCHANGE.equals(exchange) || management.findExchange(exchange).isPresent();
    }

    record Destination(String exchange, String routingKey) {
    }

    // ---------------------------------------------------------------- apoyo

    private ManagementQueue requireQueue(String name) {
        return management.findQueue(name)
                .orElseThrow(() -> new ResourceNotFoundException("La cola '%s' no existe".formatted(name)));
    }

    private ManagementExchange requireExchange(String name) {
        return management.findExchange(name)
                .orElseThrow(() -> new ResourceNotFoundException("El exchange '%s' no existe".formatted(name)));
    }

    private Optional<ManagementBinding> findBinding(String exchange, String queue, String routingKey) {
        return management.bindingsBetween(exchange, queue).stream()
                .filter(binding -> routingKey.equals(binding.routingKey()))
                .findFirst();
    }

    /** Los nombres de la topología base los declara su servicio dueño con argumentos exactos. */
    private void rejectReservedName(String name) {
        if (properties.isProtected(name)) {
            throw new ResourceConflictException(("'%s' es un nombre reservado de la topología base de Pedidos360; "
                    + "lo declara su microservicio dueño").formatted(name));
        }
    }

    private static Queue buildQueue(CreateQueueRequest request) {
        QueueBuilder builder = request.durable() ? QueueBuilder.durable(request.name())
                : QueueBuilder.nonDurable(request.name());
        if (request.type() == QueueType.QUORUM) {
            builder.quorum();
        } else {
            builder.classic();
        }
        if (request.autoDelete()) {
            builder.autoDelete();
        }
        if (request.deadLetterExchange() != null) {
            builder.deadLetterExchange(request.deadLetterExchange());
        }
        if (request.deadLetterRoutingKey() != null) {
            builder.deadLetterRoutingKey(request.deadLetterRoutingKey());
        }
        if (request.messageTtlMs() != null) {
            builder.ttl(request.messageTtlMs());
        }
        if (request.maxLength() != null) {
            builder.maxLength(request.maxLength().longValue());
        }
        return builder.build();
    }

    private static String amqpType(ExchangeType type) {
        return switch (type) {
            case DIRECT -> ExchangeTypes.DIRECT;
            case TOPIC -> ExchangeTypes.TOPIC;
            case FANOUT -> ExchangeTypes.FANOUT;
            case HEADERS -> ExchangeTypes.HEADERS;
        };
    }

    static boolean hasWildcards(String routingKey) {
        for (String segment : routingKey.split("\\.")) {
            if (segment.equals("*") || segment.equals("#")) {
                return true;
            }
        }
        return false;
    }

    private QueueResponse toResponse(ManagementQueue queue) {
        return new QueueResponse(queue.name(), upper(queue.type()), queue.durable(), queue.autoDelete(),
                arguments(queue.arguments()), count(queue.messages()), count(queue.messagesReady()),
                count(queue.messagesUnacknowledged()), count(queue.consumers()), properties.isProtected(queue.name()));
    }

    private static Map<String, Object> arguments(Map<String, Object> arguments) {
        return arguments == null ? Map.of() : arguments;
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** Ejecuta una operación AMQP y traduce los errores del broker a excepciones de dominio. */
    private <T> T broker(String action, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AmqpException exception) {
            throw translate(action, exception);
        }
    }

    static RuntimeException translate(String action, AmqpException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof BrokerUnavailableException unavailable) {
                return unavailable;
            }
            if (cause instanceof ShutdownSignalException signal && !signal.isHardError()
                    && signal.getReason() instanceof AMQP.Channel.Close close) {
                String detail = close.getReplyText();
                return switch (close.getReplyCode()) {
                    case AMQP.NOT_FOUND -> new ResourceNotFoundException(
                            "RabbitMQ no encontró el recurso al " + action + ": " + detail);
                    case AMQP.PRECONDITION_FAILED, AMQP.RESOURCE_LOCKED, AMQP.ACCESS_REFUSED ->
                            new ResourceConflictException("RabbitMQ rechazó la operación (" + action + "): " + detail);
                    default -> new BrokerUnavailableException("RabbitMQ no pudo " + action + ": " + detail, exception);
                };
            }
        }
        return new BrokerUnavailableException("RabbitMQ no está disponible para " + action, exception);
    }
}
