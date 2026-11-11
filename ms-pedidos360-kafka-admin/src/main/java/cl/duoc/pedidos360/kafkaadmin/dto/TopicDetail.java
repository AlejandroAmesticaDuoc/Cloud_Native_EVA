package cl.duoc.pedidos360.kafkaadmin.dto;

import java.util.List;
import java.util.Map;

/** Detalle de un tópico: particiones con líder, réplicas e ISR, y sus configuraciones administrables vigentes. */
public record TopicDetail(String name, String topicId, boolean internal, boolean protectedTopic, int replicationFactor,
        List<PartitionInfo> partitions, Map<String, String> configs) {
    public record PartitionInfo(int partition, Integer leader, List<Integer> replicas, List<Integer> inSyncReplicas) {}
}
