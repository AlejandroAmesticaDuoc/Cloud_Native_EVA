package cl.duoc.pedidos360.kafkaadmin.service;

import cl.duoc.pedidos360.kafkaadmin.dto.KafkaDtos.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class KafkaAdminService {

    private static final Set<String> PROTECTED_TOPICS = Set.of("orders.events", "orders.dlq");

    private final AdminClient adminClient;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaAdminService(AdminClient adminClient, KafkaTemplate<String, String> kafkaTemplate) {
        this.adminClient = adminClient;
        this.kafkaTemplate = kafkaTemplate;
    }

    public List<TopicSummary> listTopics() {
        try {
            Set<String> names = adminClient.listTopics().names().get(5, TimeUnit.SECONDS);
            List<String> userTopics = names.stream().filter(n -> !n.startsWith("__")).sorted().toList();
            if (userTopics.isEmpty()) return List.of();

            Map<String, TopicDescription> descriptions = adminClient.describeTopics(userTopics).allTopicNames().get(5, TimeUnit.SECONDS);
            List<ConfigResource> resources = userTopics.stream()
                    .map(t -> new ConfigResource(ConfigResource.Type.TOPIC, t))
                    .toList();
            Map<ConfigResource, Config> configs = adminClient.describeConfigs(resources).all().get(5, TimeUnit.SECONDS);

            List<TopicSummary> result = new ArrayList<>();
            for (String topic : userTopics) {
                TopicDescription desc = descriptions.get(topic);
                Config cfg = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, topic));
                String retention = cfg != null && cfg.get("retention.ms") != null ? cfg.get("retention.ms").value() : "604800000";
                String cleanup = cfg != null && cfg.get("cleanup.policy") != null ? cfg.get("cleanup.policy").value() : "delete";
                int rf = desc.partitions().isEmpty() ? 1 : desc.partitions().get(0).replicas().size();
                result.add(new TopicSummary(topic, desc.partitions().size(), rf, retention, cleanup, PROTECTED_TOPICS.contains(topic)));
            }
            return result;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error consultando tópicos en Kafka: " + e.getMessage());
        }
    }

    public OperationResponse createTopic(CreateTopicRequest req) {
        try {
            Map<String, String> configs = new HashMap<>();
            configs.put("retention.ms", (req.retentionMs() == null || req.retentionMs().isBlank()) ? "604800000" : req.retentionMs());
            configs.put("cleanup.policy", (req.cleanupPolicy() == null || req.cleanupPolicy().isBlank()) ? "delete" : req.cleanupPolicy());

            NewTopic newTopic = new NewTopic(req.name().trim(), req.partitions(), req.replicationFactor()).configs(configs);
            adminClient.createTopics(List.of(newTopic)).all().get(5, TimeUnit.SECONDS);
            return new OperationResponse(true, "Tópico creado: " + req.name(), Map.of("topic", req.name(), "configs", configs));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo crear el tópico: " + e.getMessage());
        }
    }

    public OperationResponse updateTopic(String topicName, UpdateTopicConfigRequest req) {
        try {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
            List<AlterConfigOp> ops = new ArrayList<>();
            if (req.retentionMs() != null && !req.retentionMs().isBlank()) {
                ops.add(new AlterConfigOp(new ConfigEntry("retention.ms", req.retentionMs()), AlterConfigOp.OpType.SET));
            }
            if (req.cleanupPolicy() != null && !req.cleanupPolicy().isBlank()) {
                ops.add(new AlterConfigOp(new ConfigEntry("cleanup.policy", req.cleanupPolicy()), AlterConfigOp.OpType.SET));
            }
            if (!ops.isEmpty()) {
                adminClient.incrementalAlterConfigs(Map.of(resource, ops)).all().get(5, TimeUnit.SECONDS);
            }
            if (req.newPartitionCount() != null && req.newPartitionCount() > 0) {
                adminClient.createPartitions(Map.of(topicName, NewPartitions.increaseTo(req.newPartitionCount()))).all().get(5, TimeUnit.SECONDS);
            }
            return new OperationResponse(true, "Configuración actualizada para " + topicName, Map.of("topic", topicName));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error actualizando tópico: " + e.getMessage());
        }
    }

    public OperationResponse deleteTopic(String topicName) {
        if (PROTECTED_TOPICS.contains(topicName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tópico base '" + topicName + "' está protegido.");
        }
        try {
            adminClient.deleteTopics(List.of(topicName)).all().get(5, TimeUnit.SECONDS);
            return new OperationResponse(true, "Tópico eliminado: " + topicName, Map.of("topic", topicName));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error eliminando tópico: " + e.getMessage());
        }
    }

    public List<ConsumerGroupSummary> listConsumerGroups() {
        try {
            Collection<ConsumerGroupListing> listings = adminClient.listConsumerGroups().all().get(5, TimeUnit.SECONDS);
            List<String> groupIds = listings.stream().map(ConsumerGroupListing::groupId).sorted().toList();
            if (groupIds.isEmpty()) return List.of();

            Map<String, ConsumerGroupDescription> descriptions = adminClient.describeConsumerGroups(groupIds).all().get(5, TimeUnit.SECONDS);
            List<ConsumerGroupSummary> result = new ArrayList<>();
            for (String id : groupIds) {
                ConsumerGroupDescription desc = descriptions.get(id);
                List<String> topics = desc.members().stream()
                        .flatMap(m -> m.assignment().topicPartitions().stream())
                        .map(tp -> tp.topic())
                        .distinct()
                        .sorted()
                        .toList();
                result.add(new ConsumerGroupSummary(id, desc.state().toString(), desc.members().size(), topics));
            }
            return result;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error consultando consumer groups: " + e.getMessage());
        }
    }

    public OperationResponse publishMessage(PublishMessageRequest req) {
        try {
            String jsonMessage = String.format(
                    "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"timestamp\":\"%s\",\"data\":%s}",
                    UUID.randomUUID(), req.eventType(), Instant.now(),
                    req.payload().trim().startsWith("{") ? req.payload().trim() : "\"" + req.payload().replace("\"", "'") + "\""
            );
            var sendResult = kafkaTemplate.send(req.topic().trim(), req.key().trim(), jsonMessage).get(5, TimeUnit.SECONDS);
            return new OperationResponse(true, "Mensaje publicado en " + req.topic(), Map.of(
                    "topic", req.topic(),
                    "partition", sendResult.getRecordMetadata().partition(),
                    "offset", sendResult.getRecordMetadata().offset()
            ));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error publicando en Kafka: " + e.getMessage());
        }
    }
}
