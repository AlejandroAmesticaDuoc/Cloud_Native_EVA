package cl.duoc.pedidos360.kafkaadmin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import cl.duoc.pedidos360.kafkaadmin.config.KafkaAdminProperties;
import cl.duoc.pedidos360.kafkaadmin.dto.OffsetResetStrategy;
import cl.duoc.pedidos360.kafkaadmin.dto.ResetOffsetsRequest;
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
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KafkaAdminServiceImplTest {
    final Admin admin = mock(Admin.class);
    final KafkaAdminProperties properties = new KafkaAdminProperties(Duration.ofSeconds(2), 50, 3,
            Set.of("orders.events", "audit.timeline"),
            Set.of("retention.ms", "retention.bytes", "cleanup.policy", "min.insync.replicas", "segment.ms", "compression.type"));
    final KafkaAdminServiceImpl service = new KafkaAdminServiceImpl(admin, properties);
    final Node broker = new Node(1, "kafka-1", 19092);

    void topicWithPartitions(String name, int partitions) {
        var infos = java.util.stream.IntStream.range(0, partitions)
                .mapToObj(partition -> new TopicPartitionInfo(partition, broker, List.of(broker), List.of(broker))).toList();
        var result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of(name, new TopicDescription(name, false, infos))));
        when(admin.describeTopics(List.of(name))).thenReturn(result);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "retention.ms|86400000|true", "retention.ms|-1|true", "retention.ms|-2|false", "retention.ms|7d|false",
            "cleanup.policy|compact,delete|true", "cleanup.policy|borrar|false", "min.insync.replicas|2|true",
            "min.insync.replicas|4|false", "compression.type|lz4|true", "compression.type|zip|false",
            "segment.ms|0|true", "unclean.leader.election.enable|true|false"})
    void validatesConfigKeysAndValues(String key, String value, boolean valid) {
        if (valid) assertDoesNotThrow(() -> service.validateConfigs(Map.of(key, value)));
        else assertThrows(InvalidAdminRequestException.class, () -> service.validateConfigs(Map.of(key, value)));
    }

    @Test
    void protectsBaseAndInternalTopics() {
        assertThrows(ResourceConflictException.class, () -> service.deleteTopic("orders.events"));
        assertThrows(ResourceConflictException.class, () -> service.deleteTopic("__consumer_offsets"));
        assertThrows(ResourceConflictException.class, () -> service.increasePartitions("audit.timeline", 6));
        verifyNoInteractions(admin);
    }

    @Test
    void onlyAllowsIncreasingPartitions() {
        topicWithPartitions("demo", 3);
        var error = assertThrows(InvalidAdminRequestException.class, () -> service.increasePartitions("demo", 3));
        assertTrue(error.getMessage().contains("ya tiene 3"));
        verify(admin, never()).createPartitions(anyMap());
    }

    @Test
    void translatesKafkaErrorsToDomainErrors() {
        assertInstanceOf(ResourceConflictException.class, KafkaAdminServiceImpl.translate(new TopicExistsException("x"), "crear"));
        assertInstanceOf(ResourceNotFoundException.class,
                KafkaAdminServiceImpl.translate(new UnknownTopicOrPartitionException("x"), "consultar"));
        assertInstanceOf(InvalidAdminRequestException.class,
                KafkaAdminServiceImpl.translate(new InvalidReplicationFactorException("RF 4 > 3 brokers"), "crear"));
        assertInstanceOf(InvalidAdminRequestException.class,
                KafkaAdminServiceImpl.translate(new InvalidTopicException("nombre"), "crear"));
        assertInstanceOf(ResourceConflictException.class, KafkaAdminServiceImpl.translate(new GroupNotEmptyException("x"), "eliminar"));
        assertInstanceOf(ResourceNotFoundException.class, KafkaAdminServiceImpl.translate(new GroupIdNotFoundException("x"), "eliminar"));
        assertInstanceOf(BrokerUnavailableException.class,
                KafkaAdminServiceImpl.translate(new org.apache.kafka.common.errors.TimeoutException("x"), "listar"));
        assertInstanceOf(BrokerUnavailableException.class, KafkaAdminServiceImpl.translate(null, "listar"));
    }

    @Test
    void createTopicPropagatesAnExistingTopicAsConflict() {
        var result = mock(CreateTopicsResult.class);
        var failed = new org.apache.kafka.common.internals.KafkaFutureImpl<Void>();
        failed.completeExceptionally(new TopicExistsException("demo"));
        when(result.all()).thenReturn(failed);
        when(admin.createTopics(any())).thenReturn(result);
        assertThrows(ResourceConflictException.class, () -> service.createTopic(
                new cl.duoc.pedidos360.kafkaadmin.dto.CreateTopicRequest("demo", 3, 3, Map.of("retention.ms", "1000"))));
    }

    @Test
    void computesLagPerPartitionFromCommittedAndEndOffsets() {
        TopicPartition p0 = new TopicPartition("orders.events", 0);
        TopicPartition p1 = new TopicPartition("orders.events", 1);
        var member = mock(MemberDescription.class);
        when(member.consumerId()).thenReturn("m-1");
        when(member.clientId()).thenReturn("pedidos360-audit");
        when(member.host()).thenReturn("/10.0.0.5");
        when(member.assignment()).thenReturn(new MemberAssignment(Set.of(p0, p1)));
        var group = mock(ConsumerGroupDescription.class);
        when(group.groupState()).thenReturn(GroupState.STABLE);
        when(group.members()).thenReturn(List.of(member));
        when(group.partitionAssignor()).thenReturn("cooperative-sticky");
        when(group.coordinator()).thenReturn(broker);
        var described = mock(DescribeConsumerGroupsResult.class);
        when(described.all()).thenReturn(KafkaFuture.completedFuture(Map.of("audit", group)));
        when(admin.describeConsumerGroups(List.of("audit"))).thenReturn(described);
        var committed = mock(ListConsumerGroupOffsetsResult.class);
        when(committed.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(
                Map.of(p0, new OffsetAndMetadata(10), p1, new OffsetAndMetadata(7))));
        when(admin.listConsumerGroupOffsets("audit")).thenReturn(committed);
        var end = mock(ListOffsetsResult.class);
        when(end.all()).thenReturn(KafkaFuture.completedFuture(Map.of(
                p0, new ListOffsetsResult.ListOffsetsResultInfo(12, -1, Optional.empty()),
                p1, new ListOffsetsResult.ListOffsetsResultInfo(7, -1, Optional.empty()))));
        when(admin.listOffsets(anyMap())).thenReturn(end);

        var detail = service.getConsumerGroup("audit");
        assertEquals("STABLE", detail.state());
        assertEquals(2, detail.totalLag());
        assertEquals(2, detail.offsets().getFirst().lag());
        assertEquals(0, detail.offsets().get(1).lag());
        assertEquals(List.of("orders.events-0", "orders.events-1"), detail.members().getFirst().assignedPartitions());
    }

    @Test
    void refusesToResetOffsetsWhileConsumersAreActive() {
        var group = mock(ConsumerGroupDescription.class);
        when(group.groupState()).thenReturn(GroupState.STABLE);
        when(group.members()).thenReturn(List.of(mock(MemberDescription.class)));
        var described = mock(DescribeConsumerGroupsResult.class);
        when(described.all()).thenReturn(KafkaFuture.completedFuture(Map.of("audit", group)));
        when(admin.describeConsumerGroups(List.of("audit"))).thenReturn(described);
        assertThrows(ResourceConflictException.class, () -> service.resetOffsets("audit",
                new ResetOffsetsRequest("orders.events", OffsetResetStrategy.EARLIEST, null)));
        verify(admin, never()).alterConsumerGroupOffsets(any(), anyMap());
    }
}
