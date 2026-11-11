package cl.duoc.pedidos360.kafkaadmin.dto;

import java.util.List;

/** Resultado del reinicio de offsets: nuevo offset por partición. */
public record ResetOffsetsResponse(String groupId, String topic, OffsetResetStrategy strategy, List<PartitionOffset> offsets) {
    public record PartitionOffset(int partition, long offset) {}
}
