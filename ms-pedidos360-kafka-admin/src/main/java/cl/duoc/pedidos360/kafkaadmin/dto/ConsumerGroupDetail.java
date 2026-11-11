package cl.duoc.pedidos360.kafkaadmin.dto;

import java.util.List;

/**
 * Detalle de un consumer group: miembros con sus particiones asignadas y, por partición, offset confirmado,
 * último offset del tópico y lag (mensajes pendientes = fin - confirmado).
 */
public record ConsumerGroupDetail(String groupId, String state, String assignor, Integer coordinator,
        List<Member> members, List<PartitionLag> offsets, long totalLag) {
    public record Member(String memberId, String clientId, String host, List<String> assignedPartitions) {}

    public record PartitionLag(String topic, int partition, long committedOffset, long endOffset, long lag) {}
}
