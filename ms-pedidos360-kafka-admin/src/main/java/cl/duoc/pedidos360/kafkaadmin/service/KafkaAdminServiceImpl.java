package cl.duoc.pedidos360.kafkaadmin.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import cl.duoc.pedidos360.kafkaadmin.config.KafkaAdminProperties;
import cl.duoc.pedidos360.kafkaadmin.dto.*;
import cl.duoc.pedidos360.kafkaadmin.exception.BrokerUnavailableException;
import cl.duoc.pedidos360.kafkaadmin.exception.InvalidAdminRequestException;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceNotFoundException;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Implementación con el cliente {@link Admin} de Kafka. Traduce las excepciones de Kafka a errores de dominio. */
@Service
public class KafkaAdminServiceImpl implements KafkaAdminService {
    private static final Logger log = LoggerFactory.getLogger(KafkaAdminServiceImpl.class);
    private static final Set<String> CLEANUP_POLICIES = Set.of("delete", "compact", "compact,delete", "delete,compact");
    private static final Set<String> COMPRESSION_TYPES = Set.of("producer", "uncompressed", "gzip", "snappy", "lz4", "zstd");

    private final Admin admin;
    private final KafkaAdminProperties properties;

    public KafkaAdminServiceImpl(Admin admin, KafkaAdminProperties properties) {
        this.admin = admin;
        this.properties = properties;
    }

    // ------------------------------------------------------------ clúster

    @Override
    public ClusterResponse cluster() {
        var result = admin.describeCluster();
        String clusterId = await(result.clusterId(), "consultar el clúster");
        Node controller = await(result.controller(), "consultar el clúster");
        var brokers = await(result.nodes(), "consultar el clúster").stream()
                .sorted(Comparator.comparingInt(Node::id))
                .map(node -> new ClusterResponse.Broker(node.id(), node.host(), node.port(), node.rack())).toList();
        return new ClusterResponse(clusterId, controller == null ? null : controller.id(), brokers);
    }

    // ------------------------------------------------------------ tópicos

    @Override
    public List<TopicSummary> listTopics(boolean includeInternal) {
        Set<String> names = await(admin.listTopics(new ListTopicsOptions().listInternal(includeInternal)).names(),
                "listar tópicos");
        if (names.isEmpty()) return List.of();
        return await(admin.describeTopics(names).allTopicNames(), "listar tópicos").values().stream()
                .sorted(Comparator.comparing(TopicDescription::name))
                .map(topic -> new TopicSummary(topic.name(), topic.partitions().size(), replicationFactor(topic),
                        topic.isInternal(), properties.isProtected(topic.name()))).toList();
    }

    @Override
    public TopicDetail getTopic(String name) {
        TopicDescription topic = describe(name);
        var resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        Config config = await(admin.describeConfigs(List.of(resource)).all(), "consultar el tópico " + name).get(resource);
        Map<String, String> configs = new TreeMap<>();
        if (config != null) {
            config.entries().stream().filter(entry -> properties.allowedConfigs().contains(entry.name()))
                    .forEach(entry -> configs.put(entry.name(), entry.value()));
        }
        var partitions = topic.partitions().stream().map(partition -> new TopicDetail.PartitionInfo(partition.partition(),
                partition.leader() == null ? null : partition.leader().id(), ids(partition.replicas()), ids(partition.isr())))
                .toList();
        return new TopicDetail(topic.name(), topic.topicId() == null ? null : topic.topicId().toString(), topic.isInternal(),
                properties.isProtected(name), replicationFactor(topic), partitions, configs);
    }

    @Override
    public TopicDetail createTopic(CreateTopicRequest request) {
        validateConfigs(request.configs());
        if (request.partitions() > properties.maxPartitions()) {
            throw new InvalidAdminRequestException("partitions no puede superar " + properties.maxPartitions());
        }
        if (request.replicationFactor() > properties.maxReplicationFactor()) {
            throw new InvalidAdminRequestException("replicationFactor no puede superar " + properties.maxReplicationFactor());
        }
        var topic = new NewTopic(request.name(), request.partitions(), request.replicationFactor().shortValue())
                .configs(request.configs());
        await(admin.createTopics(List.of(topic)).all(), "crear el tópico " + request.name());
        log.info("Tópico creado name={} partitions={} replicationFactor={} configs={}", request.name(),
                request.partitions(), request.replicationFactor(), request.configs());
        return getTopic(request.name());
    }

    @Override
    public TopicDetail updateTopicConfigs(String name, UpdateTopicConfigsRequest request) {
        validateConfigs(request.set());
        request.reset().forEach(this::requireAllowed);
        describe(name);
        List<AlterConfigOp> operations = new ArrayList<>();
        request.set().forEach((key, value) ->
                operations.add(new AlterConfigOp(new ConfigEntry(key, value), AlterConfigOp.OpType.SET)));
        request.reset().forEach(key ->
                operations.add(new AlterConfigOp(new ConfigEntry(key, null), AlterConfigOp.OpType.DELETE)));
        var resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        await(admin.incrementalAlterConfigs(Map.of(resource, operations)).all(), "modificar el tópico " + name);
        log.info("Configuración de tópico modificada name={} set={} reset={}", name, request.set(), request.reset());
        return getTopic(name);
    }

    @Override
    public TopicDetail increasePartitions(String name, int totalPartitions) {
        if (properties.isProtected(name)) {
            throw new ResourceConflictException("No se puede cambiar el número de particiones de '" + name
                    + "': es un tópico base y cambiaría la partición de cada pedido (se perdería el orden de sus eventos)");
        }
        if (totalPartitions > properties.maxPartitions()) {
            throw new InvalidAdminRequestException("totalPartitions no puede superar " + properties.maxPartitions());
        }
        int current = describe(name).partitions().size();
        if (totalPartitions <= current) {
            throw new InvalidAdminRequestException("Kafka solo permite aumentar particiones: el tópico '" + name
                    + "' ya tiene " + current + " y se pidieron " + totalPartitions);
        }
        await(admin.createPartitions(Map.of(name, NewPartitions.increaseTo(totalPartitions))).all(),
                "aumentar particiones de " + name);
        log.info("Particiones aumentadas name={} de {} a {}", name, current, totalPartitions);
        return getTopic(name);
    }

    @Override
    public void deleteTopic(String name) {
        if (properties.isProtected(name)) {
            throw new ResourceConflictException("El tópico '" + name + "' es parte de la topología base y no se puede eliminar");
        }
        await(admin.deleteTopics(List.of(name)).all(), "eliminar el tópico " + name);
        log.info("Tópico eliminado name={}", name);
    }

    // ------------------------------------------------------------ consumer groups

    @Override
    public List<ConsumerGroupSummary> listConsumerGroups() {
        Collection<GroupListing> groups = await(admin.listGroups(ListGroupsOptions.forConsumerGroups()).all(),
                "listar consumer groups");
        return groups.stream().sorted(Comparator.comparing(GroupListing::groupId))
                .map(group -> new ConsumerGroupSummary(group.groupId(),
                        group.groupState().map(Enum::name).orElse(GroupState.UNKNOWN.name()))).toList();
    }

    @Override
    public ConsumerGroupDetail getConsumerGroup(String groupId) {
        ConsumerGroupDescription group = describeGroup(groupId);
        Map<TopicPartition, OffsetAndMetadata> committed = committedOffsets(groupId);
        if (group.groupState() == GroupState.DEAD && group.members().isEmpty() && committed.isEmpty()) {
            throw new ResourceNotFoundException("El consumer group '" + groupId + "' no existe");
        }
        Map<TopicPartition, Long> end = endOffsets(committed.keySet());
        List<ConsumerGroupDetail.PartitionLag> offsets = committed.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<TopicPartition, OffsetAndMetadata> entry) -> entry.getKey().topic())
                        .thenComparingInt(entry -> entry.getKey().partition()))
                .map(entry -> {
                    long position = entry.getValue().offset();
                    long last = end.getOrDefault(entry.getKey(), position);
                    return new ConsumerGroupDetail.PartitionLag(entry.getKey().topic(), entry.getKey().partition(),
                            position, last, Math.max(0, last - position));
                }).toList();
        var members = group.members().stream().sorted(Comparator.comparing(MemberDescription::consumerId))
                .map(member -> new ConsumerGroupDetail.Member(member.consumerId(), member.clientId(), member.host(),
                        member.assignment().topicPartitions().stream()
                                .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                                .map(TopicPartition::toString).toList())).toList();
        long totalLag = offsets.stream().mapToLong(ConsumerGroupDetail.PartitionLag::lag).sum();
        return new ConsumerGroupDetail(groupId, group.groupState().name(), group.partitionAssignor(),
                group.coordinator() == null ? null : group.coordinator().id(), members, offsets, totalLag);
    }

    @Override
    public ResetOffsetsResponse resetOffsets(String groupId, ResetOffsetsRequest request) {
        ConsumerGroupDescription group = describeGroup(groupId);
        if (!group.members().isEmpty() || (group.groupState() != GroupState.EMPTY && group.groupState() != GroupState.DEAD)) {
            throw new ResourceConflictException("El consumer group '" + groupId + "' tiene consumidores activos ("
                    + group.groupState() + "); detén los consumidores antes de reiniciar sus offsets");
        }
        TopicDescription topic = describe(request.topic());
        OffsetSpec spec = switch (request.strategy()) {
            case EARLIEST -> OffsetSpec.earliest();
            case LATEST -> OffsetSpec.latest();
            case TIMESTAMP -> OffsetSpec.forTimestamp(request.timestamp().toEpochMilli());
        };
        Map<TopicPartition, OffsetSpec> query = new HashMap<>();
        topic.partitions().forEach(partition -> query.put(new TopicPartition(topic.name(), partition.partition()), spec));
        var found = await(admin.listOffsets(query).all(), "consultar offsets de " + topic.name());
        Map<TopicPartition, Long> latest = request.strategy() == OffsetResetStrategy.TIMESTAMP
                ? endOffsets(query.keySet()) : Map.of();
        Map<TopicPartition, OffsetAndMetadata> target = new LinkedHashMap<>();
        found.forEach((partition, info) -> target.put(partition, new OffsetAndMetadata(
                // Sin mensajes desde esa fecha Kafka devuelve -1: el grupo queda al final de la partición.
                info.offset() >= 0 ? info.offset() : latest.getOrDefault(partition, 0L))));
        await(admin.alterConsumerGroupOffsets(groupId, target).all(), "reiniciar offsets de " + groupId);
        log.info("Offsets reiniciados groupId={} topic={} strategy={} offsets={}", groupId, topic.name(),
                request.strategy(), target);
        var offsets = target.entrySet().stream().sorted(Comparator.comparingInt(entry -> entry.getKey().partition()))
                .map(entry -> new ResetOffsetsResponse.PartitionOffset(entry.getKey().partition(), entry.getValue().offset()))
                .toList();
        return new ResetOffsetsResponse(groupId, topic.name(), request.strategy(), offsets);
    }

    @Override
    public void deleteConsumerGroup(String groupId) {
        await(admin.deleteConsumerGroups(List.of(groupId)).all(), "eliminar el consumer group " + groupId);
        log.info("Consumer group eliminado groupId={}", groupId);
    }

    // ------------------------------------------------------------ apoyo

    private TopicDescription describe(String name) {
        return await(admin.describeTopics(List.of(name)).allTopicNames(), "consultar el tópico " + name).get(name);
    }

    private ConsumerGroupDescription describeGroup(String groupId) {
        return await(admin.describeConsumerGroups(List.of(groupId)).all(), "consultar el consumer group " + groupId)
                .get(groupId);
    }

    private Map<TopicPartition, OffsetAndMetadata> committedOffsets(String groupId) {
        Map<TopicPartition, OffsetAndMetadata> offsets = await(
                admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata(), "consultar offsets de " + groupId);
        return offsets.entrySet().stream().filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) return Map.of();
        Map<TopicPartition, OffsetSpec> query = partitions.stream()
                .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest()));
        return await(admin.listOffsets(query).all(), "consultar el final de las particiones").entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
    }

    private static int replicationFactor(TopicDescription topic) {
        return topic.partitions().isEmpty() ? 0 : topic.partitions().getFirst().replicas().size();
    }

    private static List<Integer> ids(List<Node> nodes) {
        return nodes.stream().map(Node::id).toList();
    }

    private void requireAllowed(String key) {
        if (!properties.allowedConfigs().contains(key)) {
            throw new InvalidAdminRequestException("La configuración '" + key + "' no se puede administrar; permitidas: "
                    + properties.allowedConfigs().stream().sorted().collect(Collectors.joining(", ")));
        }
    }

    /** Valida claves permitidas y valores antes de enviarlos al clúster, con mensajes claros. */
    void validateConfigs(Map<String, String> configs) {
        configs.forEach((key, value) -> {
            requireAllowed(key);
            String trimmed = value.trim();
            switch (key) {
                case "cleanup.policy" -> {
                    if (!CLEANUP_POLICIES.contains(trimmed)) {
                        throw new InvalidAdminRequestException("cleanup.policy debe ser delete, compact o compact,delete");
                    }
                }
                case "compression.type" -> {
                    if (!COMPRESSION_TYPES.contains(trimmed)) {
                        throw new InvalidAdminRequestException("compression.type debe ser uno de: "
                                + COMPRESSION_TYPES.stream().sorted().collect(Collectors.joining(", ")));
                    }
                }
                case "retention.ms", "retention.bytes" -> requireNumber(key, trimmed, -1, Long.MAX_VALUE);
                case "min.insync.replicas" -> requireNumber(key, trimmed, 1, properties.maxReplicationFactor());
                default -> requireNumber(key, trimmed, 0, Long.MAX_VALUE);
            }
        });
    }

    private static void requireNumber(String key, String value, long minimum, long maximum) {
        long number;
        try {
            number = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidAdminRequestException(key + " debe ser un número entero");
        }
        if (number < minimum || number > maximum) {
            throw new InvalidAdminRequestException(key + " debe estar entre " + minimum + " y " + maximum);
        }
    }

    private <T> T await(KafkaFuture<T> future, String action) {
        try {
            return future.get(properties.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Se interrumpió la operación al " + action, exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new BrokerUnavailableException("Kafka no respondió a tiempo al " + action, exception);
        } catch (ExecutionException exception) {
            throw translate(exception.getCause(), action);
        }
    }

    /** Traduce las excepciones del cliente Kafka a errores de dominio con un mensaje claro. */
    static RuntimeException translate(Throwable cause, String action) {
        String detail = cause == null || cause.getMessage() == null ? "" : ": " + cause.getMessage();
        return switch (cause) {
            case TopicExistsException e -> new ResourceConflictException("No se pudo " + action + ": el tópico ya existe");
            case UnknownTopicOrPartitionException e -> new ResourceNotFoundException("No se pudo " + action + ": el tópico no existe");
            case GroupIdNotFoundException e -> new ResourceNotFoundException("No se pudo " + action + ": el consumer group no existe");
            case GroupNotEmptyException e -> new ResourceConflictException("No se pudo " + action
                    + ": el consumer group tiene consumidores activos");
            case UnknownMemberIdException e -> new ResourceConflictException("No se pudo " + action
                    + ": el consumer group tiene consumidores activos");
            case InvalidPartitionsException e -> new InvalidAdminRequestException("No se pudo " + action + detail);
            case InvalidReplicationFactorException e -> new InvalidAdminRequestException("No se pudo " + action + detail);
            // Incluye InvalidTopicException (nombre de tópico inválido), que es una subclase.
            case InvalidConfigurationException e -> new InvalidAdminRequestException("No se pudo " + action + detail);
            case InvalidRequestException e -> new InvalidAdminRequestException("No se pudo " + action + detail);
            case PolicyViolationException e -> new InvalidAdminRequestException("No se pudo " + action + detail);
            case org.apache.kafka.common.errors.TimeoutException e -> new BrokerUnavailableException(
                    "Kafka no respondió a tiempo al " + action, e);
            case RetriableException e -> new BrokerUnavailableException("Kafka no está disponible para " + action, e);
            case null, default -> new BrokerUnavailableException("No se pudo " + action + detail, cause);
        };
    }
}
