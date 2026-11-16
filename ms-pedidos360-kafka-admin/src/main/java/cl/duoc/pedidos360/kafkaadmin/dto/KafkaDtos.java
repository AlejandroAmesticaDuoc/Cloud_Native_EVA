package cl.duoc.pedidos360.kafkaadmin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public class KafkaDtos {

    public record TopicSummary(
            String name,
            int partitions,
            int replicationFactor,
            String retentionMs,
            String cleanupPolicy,
            boolean protectedTopic
    ) {}

    public record CreateTopicRequest(
            @NotBlank String name,
            @Min(1) int partitions,
            @Min(1) short replicationFactor,
            String retentionMs,
            String cleanupPolicy
    ) {}

    public record UpdateTopicConfigRequest(
            String retentionMs,
            String cleanupPolicy,
            Integer newPartitionCount
    ) {}

    public record PublishMessageRequest(
            @NotBlank String topic,
            @NotBlank String key,
            @NotBlank String eventType,
            @NotBlank String payload
    ) {}

    public record ConsumerGroupSummary(
            String groupId,
            String state,
            int memberCount,
            List<String> assignedTopics
    ) {}

    public record OperationResponse(
            boolean success,
            String message,
            Map<String, Object> details
    ) {}
}
